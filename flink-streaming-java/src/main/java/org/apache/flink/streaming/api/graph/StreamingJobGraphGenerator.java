/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.Function;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.operators.util.UserCodeObjectWrapper;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.optimizer.plantranslate.JobGraphGenerator;
import org.apache.flink.runtime.checkpoint.CheckpointRetentionPolicy;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.InputFormatVertex;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.ScheduleMode;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointingSettings;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.operators.util.TaskConfig;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.checkpoint.WithMasterCheckpointHook;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.operators.AbstractUdfStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RescalePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.tasks.StreamIterationHead;
import org.apache.flink.streaming.runtime.tasks.StreamIterationTail;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.SerializedValue;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The StreamingJobGraphGenerator converts a {@link StreamGraph} into a {@link JobGraph}.
 */
@Internal
public class StreamingJobGraphGenerator {

	private static final Logger LOG = LoggerFactory.getLogger(StreamingJobGraphGenerator.class);

	// ------------------------------------------------------------------------

	public static JobGraph createJobGraph(StreamGraph streamGraph) {
		return createJobGraph(streamGraph, null);
	}

	public static JobGraph createJobGraph(StreamGraph streamGraph, @Nullable JobID jobID) {
		return new StreamingJobGraphGenerator(streamGraph, jobID).createJobGraph();
	}

	// ------------------------------------------------------------------------

	private final StreamGraph streamGraph;

	private final Map<Integer, JobVertex> jobVertices;
	private final JobGraph jobGraph;
	private final Collection<Integer> builtVertices;

	private final List<StreamEdge> physicalEdgesInOrder;

	private final Map<Integer, Map<Integer, StreamConfig>> chainedConfigs;

	private final Map<Integer, StreamConfig> vertexConfigs;
	private final Map<Integer, String> chainedNames;

	private final Map<Integer, ResourceSpec> chainedMinResources;
	private final Map<Integer, ResourceSpec> chainedPreferredResources;

	private final StreamGraphHasher defaultStreamGraphHasher;
	private final List<StreamGraphHasher> legacyStreamGraphHashers;

	private StreamingJobGraphGenerator(StreamGraph streamGraph) {
		this(streamGraph, null);
	}

	private StreamingJobGraphGenerator(StreamGraph streamGraph, @Nullable JobID jobID) {
		this.streamGraph = streamGraph;
		this.defaultStreamGraphHasher = new StreamGraphHasherV2();
		this.legacyStreamGraphHashers = Arrays.asList(new StreamGraphUserHashHasher());

		this.jobVertices = new HashMap<>();
		this.builtVertices = new HashSet<>();
		this.chainedConfigs = new HashMap<>();
		this.vertexConfigs = new HashMap<>();
		this.chainedNames = new HashMap<>();
		this.chainedMinResources = new HashMap<>();
		this.chainedPreferredResources = new HashMap<>();
		this.physicalEdgesInOrder = new ArrayList<>();

		jobGraph = new JobGraph(jobID, streamGraph.getJobName());
	}

	private JobGraph createJobGraph() {

		// make sure that all vertices start immediately
		// 设置启动模式为所有节点均在一开始就启动
		jobGraph.setScheduleMode(ScheduleMode.EAGER);

		// Generate deterministic hashes for the nodes in order to identify them across
		// submission iff they didn't change.
		/** 第一步 */
		/**
		 * 1.1
		 * 广度优先遍历StreamGraph，并且为每个SteamNode生成散列值, 这里的散列值产生算法，可以保证如果提交的拓扑没有改变，则每次生成的散列值都是一样的。
		 * 一个StreamNode的ID对应一个散列值。
		 */
		Map<Integer, byte[]> hashes = defaultStreamGraphHasher.traverseStreamGraphAndGenerateHashes(streamGraph);

		// Generate legacy(遗产  传统 ) version hashes for backwards compatibility
		// 为了保持兼容性创建的hash
		List<Map<Integer, byte[]>> legacyHashes = new ArrayList<>(legacyStreamGraphHashers.size());
		for (StreamGraphHasher hasher : legacyStreamGraphHashers) {
			legacyHashes.add(hasher.traverseStreamGraphAndGenerateHashes(streamGraph));
		}
        /** 相连接的操作符的散列值对 */
		Map<Integer, List<Tuple2<byte[], byte[]>>> chainedOperatorHashes = new HashMap<>();
		/**
		 * 第二步
		 * 最重要的函数，生成JobVertex，JobEdge等，并尽可能地将多个节点chain在一起
		 */
		setChaining(hashes, legacyHashes, chainedOperatorHashes);
		/**
		 * 第三步
		 * 将每个JobVertex的入边集合也序列化到该JobVertex的StreamConfig中
		 * (出边集合已经在setChaining的时候写入了)
		 */
		setPhysicalEdges();
		/**
		 * 第四步
		 * 据group name，为每个 JobVertex 指定所属的 SlotSharingGroup,
		 * 以及针对 Iteration的头尾设置  CoLocationGroup
		 */
		setSlotSharingAndCoLocation();
		/**
		 * 第五步
		 * 配置checkpoint
		 */
		configureCheckpointing();

		JobGraphGenerator.addUserArtifactEntries(streamGraph.getEnvironment().getCachedFiles(), jobGraph);

		// set the ExecutionConfig last when it has been finalized
		// 传递执行环境配置
		try {
			jobGraph.setExecutionConfig(streamGraph.getExecutionConfig());
		} catch (IOException e) {
			throw new IllegalConfigurationException("Could not serialize the ExecutionConfig." +
				"This indicates that non-serializable types (like custom serializers) were registered");
		}
		/** 返回转化好的jobGraph */
		return jobGraph;
	}

	private void setPhysicalEdges() {
		Map<Integer, List<StreamEdge>> physicalInEdgesInOrder = new HashMap<Integer, List<StreamEdge>>();

		for (StreamEdge edge : physicalEdgesInOrder) {
			int target = edge.getTargetId();

			List<StreamEdge> inEdges = physicalInEdgesInOrder.get(target);

			// create if not set
			if (inEdges == null) {
				inEdges = new ArrayList<>();
				physicalInEdgesInOrder.put(target, inEdges);
			}

			inEdges.add(edge);
		}

		for (Map.Entry<Integer, List<StreamEdge>> inEdges : physicalInEdgesInOrder.entrySet()) {
			int vertex = inEdges.getKey();
			List<StreamEdge> edgeList = inEdges.getValue();

			vertexConfigs.get(vertex).setInPhysicalEdges(edgeList);
		}
	}

	/**
	 * Sets up task chains from the source {@link StreamNode} instances.
	 * <p>
	 * <p>This will recursively(递归地) create all {@link JobVertex} instances.
	 */
	private void setChaining(Map<Integer, byte[]> hashes, List<Map<Integer, byte[]>> legacyHashes, Map<Integer, List<Tuple2<byte[], byte[]>>> chainedOperatorHashes) {
		for (Integer sourceNodeId : streamGraph.getSourceIDs()) {
			createChain(sourceNodeId, sourceNodeId, hashes, legacyHashes, 0, chainedOperatorHashes);
		}
	}

	private List<StreamEdge> createChain(
		Integer startNodeId,
		Integer currentNodeId,
		Map<Integer, byte[]> hashes,
		List<Map<Integer, byte[]>> legacyHashes,
		int chainIndex,
		Map<Integer, List<Tuple2<byte[], byte[]>>> chainedOperatorHashes) {
		/** builtVertices这个集合是用来存放已经构建好的StreamNode的id */
		if (!builtVertices.contains(startNodeId)) {
			/**
			 * 过渡用的出边集合, 用来生成最终的 JobEdge,
			 * 注意：存在某些StreamNode会连接到一起，比如source->map->flatMap，
			 * 如果这几个StreamNode连接到一起，则transitiveOutEdges是不包括 chain 内部的边，既不包含source->map的StreamEdge的 */
			List<StreamEdge> transitiveOutEdges = new ArrayList<StreamEdge>();
			/** 可以与当前节点链接的StreamEdge */
			List<StreamEdge> chainableOutputs = new ArrayList<StreamEdge>();
			/** 不可以与当前节点链接的StreamEdge */
			List<StreamEdge> nonChainableOutputs = new ArrayList<StreamEdge>();
			/** 将当前节点的出边分成 chainable 和 nonChainable 两类 */
			for (StreamEdge outEdge : streamGraph.getStreamNode(currentNodeId).getOutEdges()) {
				if (isChainable(outEdge, streamGraph)) {
					chainableOutputs.add(outEdge);
				} else {
					nonChainableOutputs.add(outEdge);
				}
			}
			/** 对于每个可连接的StreamEdge，递归调用其目标StreamNode，startNodeId保持不变，但是chainIndex会加1 */
			for (StreamEdge chainable : chainableOutputs) {
				transitiveOutEdges.addAll(
					createChain(startNodeId, chainable.getTargetId(), hashes, legacyHashes, chainIndex + 1, chainedOperatorHashes));
			}
/**
 * 对于每个不可连接的StreamEdge，则将对于的StreamEdge就是当前链的一个输出StreamEdge，所以会添加到transitiveOutEdges这个集合中
 * 然后递归调用其目标节点，注意，startNodeID变成了nonChainable这个StreamEdge的输出节点id，chainIndex也赋值为0，说明重新开始一条链的建立
 */
			for (StreamEdge nonChainable : nonChainableOutputs) {
				transitiveOutEdges.add(nonChainable);
				createChain(nonChainable.getTargetId(), nonChainable.getTargetId(), hashes, legacyHashes, 0, chainedOperatorHashes);
			}
			/** 获取链起始节点对应的操作符散列值列表，如果没有，则是空列表 */
			List<Tuple2<byte[], byte[]>> operatorHashes =
				chainedOperatorHashes.computeIfAbsent(startNodeId, k -> new ArrayList<>());
/** 当前 StreamNode 对应的主散列值 */
			byte[] primaryHashBytes = hashes.get(currentNodeId);
/** 遍历每个备用散列值，并与主散列值，组成一个二元组，添加到列表中 */
			for (Map<Integer, byte[]> legacyHash : legacyHashes) {
				operatorHashes.add(new Tuple2<>(primaryHashBytes, legacyHash.get(currentNodeId)));
			}
/** 生成当前节点的显示名，如："Keyed Aggregation -> Sink: Unnamed" */
			chainedNames.put(currentNodeId, createChainedName(currentNodeId, chainableOutputs));
			chainedMinResources.put(currentNodeId, createChainedMinResources(currentNodeId, chainableOutputs));
			chainedPreferredResources.put(currentNodeId, createChainedPreferredResources(currentNodeId, chainableOutputs));
/**
 * 1、如果当前节点是起始节点, 则直接创建 JobVertex 并返回 StreamConfig,
 * 2、否则先创建一个空的 StreamConfig
 *
 * createJobVertex 函数就是根据 StreamNode 创建对应的 JobVertex, 并返回了空的 StreamConfig
 */

			StreamConfig config = currentNodeId.equals(startNodeId)
				? createJobVertex(startNodeId, hashes, legacyHashes, chainedOperatorHashes)
				: new StreamConfig(new Configuration());


			/**
			 * {@link StreamConfig}就是对{@link Configuration}的封装，
			 * 所以通过{@code StreamConfig}设置的配置，最终都是保存在{@code Configuration}中的。
			 */

			/**
			 * 设置 JobVertex 的 StreamConfig, 基本上是序列化 StreamNode 中的配置到 StreamConfig 中.
			 * 其中包括 序列化器, StreamOperator, Checkpoint 等相关配置
			 * 经过这一步操作后，StreamNode的相关配置会通过对{@code StreamNode}的设置接口，将配置保存在{@code Configuration}中，
			 * 而{@code Configuration}是是{@link JobVertex}的属性，也就是说经过这步操作，相关配置已经被保存到了{@code JobVertex}中。
			 */

			setVertexConfig(currentNodeId, config, chainableOutputs, nonChainableOutputs);

			if (currentNodeId.equals(startNodeId)) {
/** 如果是chain的起始节点。（不是chain的中间节点，会被标记成 chain start）*/
				config.setChainStart();
				config.setChainIndex(0);
				config.setOperatorName(streamGraph.getStreamNode(currentNodeId).getOperatorName());
				/** 我们也会把物理出边写入配置, 部署时会用到 */
				config.setOutEdgesInOrder(transitiveOutEdges);
				config.setOutEdges(streamGraph.getStreamNode(currentNodeId).getOutEdges());

				for (StreamEdge edge : transitiveOutEdges) {
					/** 将当前节点(headOfChain)与所有出边相连 */
					connect(startNodeId, edge);
				}
/** 将chain中所有子节点的StreamConfig写入到 headOfChain 节点的 CHAINED_TASK_CONFIG 配置中 */
				config.setTransitiveChainedTaskConfigs(chainedConfigs.get(startNodeId));

			} else {
/** 如果是 chain 中的子节点 */
				Map<Integer, StreamConfig> chainedConfs = chainedConfigs.get(startNodeId);

				if (chainedConfs == null) {
					chainedConfigs.put(startNodeId, new HashMap<Integer, StreamConfig>());
				}
				config.setChainIndex(chainIndex);
				StreamNode node = streamGraph.getStreamNode(currentNodeId);
				config.setOperatorName(node.getOperatorName());
				/** 将当前节点的StreamConfig添加到该chain的config集合中 */
				chainedConfigs.get(startNodeId).put(currentNodeId, config);
			}

			config.setOperatorID(new OperatorID(primaryHashBytes));
/** 如果节点的输出StreamEdge已经为空，则说明是链的结尾 */
			if (chainableOutputs.isEmpty()) {
				config.setChainEnd();
			}
			return transitiveOutEdges;

		} else {
			/** startNodeId 如果已经构建过,则直接返回 */
			return new ArrayList<>();
		}
	}

	private String createChainedName(Integer vertexID, List<StreamEdge> chainedOutputs) {
		String operatorName = streamGraph.getStreamNode(vertexID).getOperatorName();
		if (chainedOutputs.size() > 1) {
			List<String> outputChainedNames = new ArrayList<>();
			for (StreamEdge chainable : chainedOutputs) {
				outputChainedNames.add(chainedNames.get(chainable.getTargetId()));
			}
			return operatorName + " -> (" + StringUtils.join(outputChainedNames, ", ") + ")";
		} else if (chainedOutputs.size() == 1) {
			return operatorName + " -> " + chainedNames.get(chainedOutputs.get(0).getTargetId());
		} else {
			return operatorName;
		}
	}

	private ResourceSpec createChainedMinResources(Integer vertexID, List<StreamEdge> chainedOutputs) {
		ResourceSpec minResources = streamGraph.getStreamNode(vertexID).getMinResources();
		for (StreamEdge chainable : chainedOutputs) {
			minResources = minResources.merge(chainedMinResources.get(chainable.getTargetId()));
		}
		return minResources;
	}

	private ResourceSpec createChainedPreferredResources(Integer vertexID, List<StreamEdge> chainedOutputs) {
		ResourceSpec preferredResources = streamGraph.getStreamNode(vertexID).getPreferredResources();
		for (StreamEdge chainable : chainedOutputs) {
			preferredResources = preferredResources.merge(chainedPreferredResources.get(chainable.getTargetId()));
		}
		return preferredResources;
	}

	private StreamConfig createJobVertex(
		Integer streamNodeId,
		Map<Integer, byte[]> hashes,
		List<Map<Integer, byte[]>> legacyHashes,
		Map<Integer, List<Tuple2<byte[], byte[]>>> chainedOperatorHashes) {

		JobVertex jobVertex;
		StreamNode streamNode = streamGraph.getStreamNode(streamNodeId);

		byte[] hash = hashes.get(streamNodeId);

		if (hash == null) {
			throw new IllegalStateException("Cannot find node hash. " +
				"Did you generate them before calling this method?");
		}

		JobVertexID jobVertexId = new JobVertexID(hash);

		List<JobVertexID> legacyJobVertexIds = new ArrayList<>(legacyHashes.size());
		for (Map<Integer, byte[]> legacyHash : legacyHashes) {
			hash = legacyHash.get(streamNodeId);
			if (null != hash) {
				legacyJobVertexIds.add(new JobVertexID(hash));
			}
		}

		List<Tuple2<byte[], byte[]>> chainedOperators = chainedOperatorHashes.get(streamNodeId);
		List<OperatorID> chainedOperatorVertexIds = new ArrayList<>();
		List<OperatorID> userDefinedChainedOperatorVertexIds = new ArrayList<>();
		if (chainedOperators != null) {
			for (Tuple2<byte[], byte[]> chainedOperator : chainedOperators) {
				chainedOperatorVertexIds.add(new OperatorID(chainedOperator.f0));
				userDefinedChainedOperatorVertexIds.add(chainedOperator.f1 != null ? new OperatorID(chainedOperator.f1) : null);
			}
		}

		if (streamNode.getInputFormat() != null) {
			jobVertex = new InputFormatVertex(
				chainedNames.get(streamNodeId),
				jobVertexId,
				legacyJobVertexIds,
				chainedOperatorVertexIds,
				userDefinedChainedOperatorVertexIds);
			TaskConfig taskConfig = new TaskConfig(jobVertex.getConfiguration());
			taskConfig.setStubWrapper(new UserCodeObjectWrapper<Object>(streamNode.getInputFormat()));
		} else {
			jobVertex = new JobVertex(
				chainedNames.get(streamNodeId),
				jobVertexId,
				legacyJobVertexIds,
				chainedOperatorVertexIds,
				userDefinedChainedOperatorVertexIds);
		}

		jobVertex.setResources(chainedMinResources.get(streamNodeId), chainedPreferredResources.get(streamNodeId));

		jobVertex.setInvokableClass(streamNode.getJobVertexClass());

		int parallelism = streamNode.getParallelism();

		if (parallelism > 0) {
			jobVertex.setParallelism(parallelism);
		} else {
			parallelism = jobVertex.getParallelism();
		}

		jobVertex.setMaxParallelism(streamNode.getMaxParallelism());

		if (LOG.isDebugEnabled()) {
			LOG.debug("Parallelism set: {} for {}", parallelism, streamNodeId);
		}

		jobVertices.put(streamNodeId, jobVertex);
		builtVertices.add(streamNodeId);
		jobGraph.addVertex(jobVertex);

		return new StreamConfig(jobVertex.getConfiguration());
	}

	@SuppressWarnings("unchecked")
	private void setVertexConfig(Integer vertexID, StreamConfig config,
								 List<StreamEdge> chainableOutputs, List<StreamEdge> nonChainableOutputs) {

		StreamNode vertex = streamGraph.getStreamNode(vertexID);

		config.setVertexID(vertexID);
		config.setBufferTimeout(vertex.getBufferTimeout());

		config.setTypeSerializerIn1(vertex.getTypeSerializerIn1());
		config.setTypeSerializerIn2(vertex.getTypeSerializerIn2());
		config.setTypeSerializerOut(vertex.getTypeSerializerOut());

		// iterate edges, find sideOutput edges create and save serializers for each outputTag type
		for (StreamEdge edge : chainableOutputs) {
			if (edge.getOutputTag() != null) {
				config.setTypeSerializerSideOut(
					edge.getOutputTag(),
					edge.getOutputTag().getTypeInfo().createSerializer(streamGraph.getExecutionConfig())
				);
			}
		}
		for (StreamEdge edge : nonChainableOutputs) {
			if (edge.getOutputTag() != null) {
				config.setTypeSerializerSideOut(
					edge.getOutputTag(),
					edge.getOutputTag().getTypeInfo().createSerializer(streamGraph.getExecutionConfig())
				);
			}
		}

		config.setStreamOperator(vertex.getOperator());
		config.setOutputSelectors(vertex.getOutputSelectors());

		config.setNumberOfOutputs(nonChainableOutputs.size());
		config.setNonChainedOutputs(nonChainableOutputs);
		config.setChainedOutputs(chainableOutputs);

		config.setTimeCharacteristic(streamGraph.getEnvironment().getStreamTimeCharacteristic());

		final CheckpointConfig checkpointCfg = streamGraph.getCheckpointConfig();

		config.setStateBackend(streamGraph.getStateBackend());
		config.setCheckpointingEnabled(checkpointCfg.isCheckpointingEnabled());
		if (checkpointCfg.isCheckpointingEnabled()) {
			config.setCheckpointMode(checkpointCfg.getCheckpointingMode());
		} else {
			// the "at-least-once" input handler is slightly cheaper (in the absence of checkpoints),
			// so we use that one if checkpointing is not enabled
			config.setCheckpointMode(CheckpointingMode.AT_LEAST_ONCE);
		}
		config.setStatePartitioner(0, vertex.getStatePartitioner1());
		config.setStatePartitioner(1, vertex.getStatePartitioner2());
		config.setStateKeySerializer(vertex.getStateKeySerializer());

		Class<? extends AbstractInvokable> vertexClass = vertex.getJobVertexClass();

		if (vertexClass.equals(StreamIterationHead.class)
			|| vertexClass.equals(StreamIterationTail.class)) {
			config.setIterationId(streamGraph.getBrokerID(vertexID));
			config.setIterationWaitTime(streamGraph.getLoopTimeout(vertexID));
		}

		List<StreamEdge> allOutputs = new ArrayList<StreamEdge>(chainableOutputs);
		allOutputs.addAll(nonChainableOutputs);

		vertexConfigs.put(vertexID, config);
	}

	private void connect(Integer headOfChain, StreamEdge edge) {

		physicalEdgesInOrder.add(edge);

		Integer downStreamvertexID = edge.getTargetId();

		JobVertex headVertex = jobVertices.get(headOfChain);
		JobVertex downStreamVertex = jobVertices.get(downStreamvertexID);

		StreamConfig downStreamConfig = new StreamConfig(downStreamVertex.getConfiguration());

		downStreamConfig.setNumberOfInputs(downStreamConfig.getNumberOfInputs() + 1);

		StreamPartitioner<?> partitioner = edge.getPartitioner();
		JobEdge jobEdge;
		if (partitioner instanceof ForwardPartitioner || partitioner instanceof RescalePartitioner) {
			jobEdge = downStreamVertex.connectNewDataSetAsInput(
				headVertex,
				DistributionPattern.POINTWISE,
				ResultPartitionType.PIPELINED_BOUNDED);
		} else {
			jobEdge = downStreamVertex.connectNewDataSetAsInput(
				headVertex,
				DistributionPattern.ALL_TO_ALL,
				ResultPartitionType.PIPELINED_BOUNDED);
		}
		// set strategy name so that web interface can show it.
		jobEdge.setShipStrategyName(partitioner.toString());

		if (LOG.isDebugEnabled()) {
			LOG.debug("CONNECTED: {} - {} -> {}", partitioner.getClass().getSimpleName(),
				headOfChain, downStreamvertexID);
		}
	}

	public static boolean isChainable(StreamEdge edge, StreamGraph streamGraph) {
		/** 获取StreamEdge的源和目标StreamNode */
		StreamNode upStreamVertex = edge.getSourceVertex();
		StreamNode downStreamVertex = edge.getTargetVertex();
        /** 获取源和目标StreamNode中的StreamOperator */
		StreamOperator<?> headOperator = upStreamVertex.getOperator();
		StreamOperator<?> outOperator = downStreamVertex.getOperator();
		/**
		 * 1、下游节点只有一个输入
		 * 2、下游节点的操作符不为null
		 * 3、上游节点的操作符不为null
		 * 4、上下游节点在一个槽位共享组内
		 * 5、下游节点的连接策略是 ALWAYS
		 * 6、上游节点的连接策略是 HEAD 或者 ALWAYS
		 * 7、edge 的分区函数是 ForwardPartitioner 的实例
		 * 8、上下游节点的并行度相等
		 * 9、可以进行节点连接操作
		 */
		return downStreamVertex.getInEdges().size() == 1
			&& outOperator != null
			&& headOperator != null
			&& upStreamVertex.isSameSlotSharingGroup(downStreamVertex)
			&& outOperator.getChainingStrategy() == ChainingStrategy.ALWAYS
			&& (headOperator.getChainingStrategy() == ChainingStrategy.HEAD ||
			headOperator.getChainingStrategy() == ChainingStrategy.ALWAYS)
			&& (edge.getPartitioner() instanceof ForwardPartitioner)
			&& upStreamVertex.getParallelism() == downStreamVertex.getParallelism()
			&& streamGraph.isChainingEnabled();
	}

	private void setSlotSharingAndCoLocation() {
		final HashMap<String, SlotSharingGroup> slotSharingGroups = new HashMap<>();
		final HashMap<String, Tuple2<SlotSharingGroup, CoLocationGroup>> coLocationGroups = new HashMap<>();

		for (Entry<Integer, JobVertex> entry : jobVertices.entrySet()) {

			final StreamNode node = streamGraph.getStreamNode(entry.getKey());
			final JobVertex vertex = entry.getValue();

			// configure slot sharing group
			final String slotSharingGroupKey = node.getSlotSharingGroup();
			final SlotSharingGroup sharingGroup;

			if (slotSharingGroupKey != null) {
				sharingGroup = slotSharingGroups.computeIfAbsent(
					slotSharingGroupKey, (k) -> new SlotSharingGroup());
				vertex.setSlotSharingGroup(sharingGroup);
			} else {
				sharingGroup = null;
			}

			// configure co-location constraint
			final String coLocationGroupKey = node.getCoLocationGroup();
			if (coLocationGroupKey != null) {
				if (sharingGroup == null) {
					throw new IllegalStateException("Cannot use a co-location constraint without a slot sharing group");
				}

				Tuple2<SlotSharingGroup, CoLocationGroup> constraint = coLocationGroups.computeIfAbsent(
					coLocationGroupKey, (k) -> new Tuple2<>(sharingGroup, new CoLocationGroup()));

				if (constraint.f0 != sharingGroup) {
					throw new IllegalStateException("Cannot co-locate operators from different slot sharing groups");
				}

				vertex.updateCoLocationGroup(constraint.f1);
			}
		}

		for (Tuple2<StreamNode, StreamNode> pair : streamGraph.getIterationSourceSinkPairs()) {

			CoLocationGroup ccg = new CoLocationGroup();

			JobVertex source = jobVertices.get(pair.f0.getId());
			JobVertex sink = jobVertices.get(pair.f1.getId());

			ccg.addVertex(source);
			ccg.addVertex(sink);
			source.updateCoLocationGroup(ccg);
			sink.updateCoLocationGroup(ccg);
		}

	}

	private void configureCheckpointing() {
		CheckpointConfig cfg = streamGraph.getCheckpointConfig();

		long interval = cfg.getCheckpointInterval();
		if (interval > 0) {
			ExecutionConfig executionConfig = streamGraph.getExecutionConfig();
			// propagate the expected behaviour for checkpoint errors to task.
			executionConfig.setFailTaskOnCheckpointError(cfg.isFailOnCheckpointingErrors());
		} else {
			// interval of max value means disable periodic checkpoint
			interval = Long.MAX_VALUE;
		}

		//  --- configure the participating vertices ---

		// collect the vertices that receive "trigger checkpoint" messages.
		// currently, these are all the sources
		List<JobVertexID> triggerVertices = new ArrayList<>();

		// collect the vertices that need to acknowledge the checkpoint
		// currently, these are all vertices
		List<JobVertexID> ackVertices = new ArrayList<>(jobVertices.size());

		// collect the vertices that receive "commit checkpoint" messages
		// currently, these are all vertices
		List<JobVertexID> commitVertices = new ArrayList<>(jobVertices.size());

		for (JobVertex vertex : jobVertices.values()) {
			if (vertex.isInputVertex()) {
				triggerVertices.add(vertex.getID());
			}
			commitVertices.add(vertex.getID());
			ackVertices.add(vertex.getID());
		}

		//  --- configure options ---

		CheckpointRetentionPolicy retentionAfterTermination;
		if (cfg.isExternalizedCheckpointsEnabled()) {
			CheckpointConfig.ExternalizedCheckpointCleanup cleanup = cfg.getExternalizedCheckpointCleanup();
			// Sanity check
			if (cleanup == null) {
				throw new IllegalStateException("Externalized checkpoints enabled, but no cleanup mode configured.");
			}
			retentionAfterTermination = cleanup.deleteOnCancellation() ?
				CheckpointRetentionPolicy.RETAIN_ON_FAILURE :
				CheckpointRetentionPolicy.RETAIN_ON_CANCELLATION;
		} else {
			retentionAfterTermination = CheckpointRetentionPolicy.NEVER_RETAIN_AFTER_TERMINATION;
		}

		CheckpointingMode mode = cfg.getCheckpointingMode();

		boolean isExactlyOnce;
		if (mode == CheckpointingMode.EXACTLY_ONCE) {
			isExactlyOnce = true;
		} else if (mode == CheckpointingMode.AT_LEAST_ONCE) {
			isExactlyOnce = false;
		} else {
			throw new IllegalStateException("Unexpected checkpointing mode. " +
				"Did not expect there to be another checkpointing mode besides " +
				"exactly-once or at-least-once.");
		}

		//  --- configure the master-side checkpoint hooks ---

		final ArrayList<MasterTriggerRestoreHook.Factory> hooks = new ArrayList<>();

		for (StreamNode node : streamGraph.getStreamNodes()) {
			StreamOperator<?> op = node.getOperator();
			if (op instanceof AbstractUdfStreamOperator) {
				Function f = ((AbstractUdfStreamOperator<?, ?>) op).getUserFunction();

				if (f instanceof WithMasterCheckpointHook) {
					hooks.add(new FunctionMasterCheckpointHookFactory((WithMasterCheckpointHook<?>) f));
				}
			}
		}

		// because the hooks can have user-defined code, they need to be stored as
		// eagerly serialized values
		final SerializedValue<MasterTriggerRestoreHook.Factory[]> serializedHooks;
		if (hooks.isEmpty()) {
			serializedHooks = null;
		} else {
			try {
				MasterTriggerRestoreHook.Factory[] asArray =
					hooks.toArray(new MasterTriggerRestoreHook.Factory[hooks.size()]);
				serializedHooks = new SerializedValue<>(asArray);
			} catch (IOException e) {
				throw new FlinkRuntimeException("Trigger/restore hook is not serializable", e);
			}
		}

		// because the state backend can have user-defined code, it needs to be stored as
		// eagerly serialized value
		final SerializedValue<StateBackend> serializedStateBackend;
		if (streamGraph.getStateBackend() == null) {
			serializedStateBackend = null;
		} else {
			try {
				serializedStateBackend =
					new SerializedValue<StateBackend>(streamGraph.getStateBackend());
			} catch (IOException e) {
				throw new FlinkRuntimeException("State backend is not serializable", e);
			}
		}

		//  --- done, put it all together ---

		JobCheckpointingSettings settings = new JobCheckpointingSettings(
			triggerVertices,
			ackVertices,
			commitVertices,
			new CheckpointCoordinatorConfiguration(
				interval,
				cfg.getCheckpointTimeout(),
				cfg.getMinPauseBetweenCheckpoints(),
				cfg.getMaxConcurrentCheckpoints(),
				retentionAfterTermination,
				isExactlyOnce),
			serializedStateBackend,
			serializedHooks);

		jobGraph.setSnapshotSettings(settings);
	}
}
