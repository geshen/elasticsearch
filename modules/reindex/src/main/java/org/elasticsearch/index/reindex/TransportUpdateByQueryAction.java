/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.byscroll.AbstractAsyncBulkByScrollAction;
import org.elasticsearch.action.bulk.byscroll.BulkByScrollResponse;
import org.elasticsearch.action.bulk.byscroll.ParentBulkByScrollTask;
import org.elasticsearch.action.bulk.byscroll.BulkByScrollParallelizationHelper;
import org.elasticsearch.action.bulk.byscroll.ScrollableHitSource;
import org.elasticsearch.action.bulk.byscroll.WorkingBulkByScrollTask;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ParentTaskAssigningClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.IndexFieldMapper;
import org.elasticsearch.index.mapper.ParentFieldMapper;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.TypeFieldMapper;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Map;
import java.util.function.BiFunction;

public class TransportUpdateByQueryAction extends HandledTransportAction<UpdateByQueryRequest, BulkByScrollResponse> {
    private final Client client;
    private final ScriptService scriptService;
    private final ClusterService clusterService;

    @Inject
    public TransportUpdateByQueryAction(Settings settings, ThreadPool threadPool, ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver, Client client, TransportService transportService,
            ScriptService scriptService, ClusterService clusterService) {
        super(settings, UpdateByQueryAction.NAME, threadPool, transportService, actionFilters,
                indexNameExpressionResolver, UpdateByQueryRequest::new);
        this.client = client;
        this.scriptService = scriptService;
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(Task task, UpdateByQueryRequest request, ActionListener<BulkByScrollResponse> listener) {
        if (request.getSlices() > 1) {
            BulkByScrollParallelizationHelper.startSlices(client, taskManager, UpdateByQueryAction.INSTANCE,
                    clusterService.localNode().getId(), (ParentBulkByScrollTask) task, request, listener);
        } else {
            ClusterState state = clusterService.state();
            ParentTaskAssigningClient client = new ParentTaskAssigningClient(this.client, clusterService.localNode(), task);
            new AsyncIndexBySearchAction((WorkingBulkByScrollTask) task, logger, client, threadPool, request, scriptService, state,
                    listener).start();
        }
    }

    @Override
    protected void doExecute(UpdateByQueryRequest request, ActionListener<BulkByScrollResponse> listener) {
        throw new UnsupportedOperationException("task required");
    }

    /**
     * Simple implementation of update-by-query using scrolling and bulk.
     */
    static class AsyncIndexBySearchAction extends AbstractAsyncBulkByScrollAction<UpdateByQueryRequest> {
        AsyncIndexBySearchAction(WorkingBulkByScrollTask task, Logger logger, ParentTaskAssigningClient client,
                ThreadPool threadPool, UpdateByQueryRequest request, ScriptService scriptService, ClusterState clusterState,
                ActionListener<BulkByScrollResponse> listener) {
            super(task, logger, client, threadPool, request, scriptService, clusterState, listener);
        }

        @Override
        protected boolean needsSourceDocumentVersions() {
            /*
             * We always need the version of the source document so we can report a version conflict if we try to delete it and it has been
             * changed.
             */
            return true;
        }

        @Override
        public BiFunction<RequestWrapper<?>, ScrollableHitSource.Hit, RequestWrapper<?>> buildScriptApplier() {
            Script script = mainRequest.getScript();
            if (script != null) {
                return new UpdateByQueryScriptApplier(task, scriptService, script, script.getParams());
            }
            return super.buildScriptApplier();
        }

        @Override
        protected RequestWrapper<IndexRequest> buildRequest(ScrollableHitSource.Hit doc) {
            IndexRequest index = new IndexRequest();
            index.index(doc.getIndex());
            index.type(doc.getType());
            index.id(doc.getId());
            index.source(doc.getSource());
            index.versionType(VersionType.INTERNAL);
            index.version(doc.getVersion());
            index.setPipeline(mainRequest.getPipeline());
            return wrap(index);
        }

        class UpdateByQueryScriptApplier extends ScriptApplier {

            UpdateByQueryScriptApplier(WorkingBulkByScrollTask task, ScriptService scriptService, Script script,
                                 Map<String, Object> params) {
                super(task, scriptService, script, params);
            }

            @Override
            protected void scriptChangedIndex(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [" + IndexFieldMapper.NAME + "] not allowed");
            }

            @Override
            protected void scriptChangedType(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [" + TypeFieldMapper.NAME + "] not allowed");
            }

            @Override
            protected void scriptChangedId(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [" + IdFieldMapper.NAME + "] not allowed");
            }

            @Override
            protected void scriptChangedVersion(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [_version] not allowed");
            }

            @Override
            protected void scriptChangedRouting(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [" + RoutingFieldMapper.NAME + "] not allowed");
            }

            @Override
            protected void scriptChangedParent(RequestWrapper<?> request, Object to) {
                throw new IllegalArgumentException("Modifying [" + ParentFieldMapper.NAME + "] not allowed");
            }

        }
    }
}
