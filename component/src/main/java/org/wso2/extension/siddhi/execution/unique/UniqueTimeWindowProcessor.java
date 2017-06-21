/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.execution.unique;

import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.query.processor.SchedulingProcessor;
import org.wso2.siddhi.core.query.processor.stream.window.FindableProcessor;
import org.wso2.siddhi.core.query.processor.stream.window.WindowProcessor;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.Scheduler;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import org.wso2.siddhi.core.util.collection.operator.Operator;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.parser.OperatorParser;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.exception.SiddhiAppValidationException;
import org.wso2.siddhi.query.api.expression.Expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
* Sample Query:
* from inputStream#window.unique:time(attribute1,3 sec)
* select attribute1, attribute2
* insert into outputStream;
*
* Description:
* In the example query given, 3 is the duration of the window and attribute1 is the unique attribute.
* According to the given attribute it will give unique events within given time.
* */

/**
 * class representing unique time window processor implementation.
 */

//TBD: annotation description
@Extension(name = "time", namespace = "unique", description = "TBD", parameters = {
        @Parameter(name = "abc.def.ghi", description = "TBD", type = {
                DataType.STRING }) }, examples = @Example(syntax = "TBD", description = "TBD"))

public class UniqueTimeWindowProcessor extends WindowProcessor implements SchedulingProcessor, FindableProcessor {

    private ConcurrentHashMap<String, StreamEvent> map = new ConcurrentHashMap<String, StreamEvent>();
    private long timeInMilliSeconds;
    private ComplexEventChunk<StreamEvent> expiredEventChunk;
    private Scheduler scheduler;
    private SiddhiAppContext siddhiAppContext;
    private volatile long lastTimestamp = Long.MIN_VALUE;
    private VariableExpressionExecutor[] variableExpressionExecutors;

    /**
     * The getScheduler method of the TimeWindowProcessor, As scheduler is private variable, to access publicly we
     * use this getter method.
     */
    @Override public synchronized Scheduler getScheduler() {
        return scheduler;
    }

    /**
     * The setScheduler method of the TimeWindowProcessor, As scheduler is private variable, to access publicly we
     * use this setter method.
     *
     * @param scheduler the value of scheduler.
     */
    @Override public synchronized void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * The init method of the WindowProcessor, this method will be called before other methods.
     *
     * @param attributeExpressionExecutors the executors of each function parameters
     * @param siddhiAppContext             the context of the execution plan
     * @param outputExpectsExpiredEvents   //TBD
     * @param configReader                 //TBD
     */
    @Override protected void init(ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader,
            boolean outputExpectsExpiredEvents, SiddhiAppContext siddhiAppContext) {
        this.siddhiAppContext = siddhiAppContext;
        this.expiredEventChunk = new ComplexEventChunk<StreamEvent>(false);
        variableExpressionExecutors = new VariableExpressionExecutor[attributeExpressionExecutors.length - 1];
        if (attributeExpressionExecutors.length == 2) {
            variableExpressionExecutors[0] = (VariableExpressionExecutor) attributeExpressionExecutors[0];
            if (attributeExpressionExecutors[1] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.INT) {
                    timeInMilliSeconds = (Integer) ((ConstantExpressionExecutor) attributeExpressionExecutors[1])
                            .getValue();

                } else if (attributeExpressionExecutors[1].getReturnType() == Attribute.Type.LONG) {
                    timeInMilliSeconds = (Long) ((ConstantExpressionExecutor) attributeExpressionExecutors[1])
                            .getValue();
                } else {
                    throw new SiddhiAppValidationException(
                            "UniqueTime window's parameter time should be either" + " int or long, but found "
                                    + attributeExpressionExecutors[0].getReturnType());
                }
            } else {
                throw new SiddhiAppValidationException(
                        "UniqueTime window should have constant for time parameter but " + "found a dynamic attribute "
                                + attributeExpressionExecutors[0].getClass().getCanonicalName());
            }
        } else {
            throw new SiddhiAppValidationException("UniqueTime window should only have two parameters "
                    + "(<string|int|bool|long|double|float> unique attribute, <int|long|time> windowTime), but found "
                    + attributeExpressionExecutors.length + " input attributes");
        }
    }

    /**
     * The main processing method that will be called upon event arrival.
     *
     * @param streamEventChunk  the stream event chunk that need to be processed
     * @param nextProcessor     the next processor to which the success events need to be passed
     * @param streamEventCloner helps to clone the incoming event for local storage or modification
     */
    @Override protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
            StreamEventCloner streamEventCloner) {
        synchronized (this) {
            while (streamEventChunk.hasNext()) {
                StreamEvent streamEvent = streamEventChunk.next();
                long currentTime = siddhiAppContext.getTimestampGenerator().currentTime();
                StreamEvent oldEvent = null;
                if (streamEvent.getType() == StreamEvent.Type.CURRENT) {
                    StreamEvent clonedEvent = streamEventCloner.copyStreamEvent(streamEvent);
                    clonedEvent.setType(StreamEvent.Type.EXPIRED);
                    StreamEvent eventClonedForMap = streamEventCloner.copyStreamEvent(streamEvent);
                    eventClonedForMap.setType(StreamEvent.Type.EXPIRED);
                    oldEvent = map.put(generateKey(eventClonedForMap), eventClonedForMap);
                    this.expiredEventChunk.add(clonedEvent);
                    if (lastTimestamp < clonedEvent.getTimestamp()) {
                        if (scheduler != null) {
                            scheduler.notifyAt(clonedEvent.getTimestamp() + timeInMilliSeconds);
                            lastTimestamp = clonedEvent.getTimestamp();
                        }
                    }
                }
                expiredEventChunk.reset();
                while (expiredEventChunk.hasNext()) {
                    StreamEvent expiredEvent = expiredEventChunk.next();
                    long timeDiff = expiredEvent.getTimestamp() - currentTime + timeInMilliSeconds;
                    if (timeDiff <= 0 || oldEvent != null) {
                        if (oldEvent != null) {
                            if (expiredEvent.equals(oldEvent)) {
                                this.expiredEventChunk.remove();
                                streamEventChunk.insertBeforeCurrent(oldEvent);
                                oldEvent.setTimestamp(currentTime);
                                oldEvent = null;
                            }
                        } else {
                            expiredEventChunk.remove();
                            expiredEvent.setTimestamp(currentTime);
                            streamEventChunk.insertBeforeCurrent(expiredEvent);
                            expiredEvent.setTimestamp(currentTime);
                            expiredEventChunk.reset();
                        }
                    } else {
                        break;
                    }
                }
                expiredEventChunk.reset();
                if (streamEvent.getType() != StreamEvent.Type.CURRENT) {
                    streamEventChunk.remove();
                }
            }
        }
        nextProcessor.process(streamEventChunk);
    }

    /**
     * To find events from the processor event pool, that the matches the matchingEvent based on finder logic.
     *
     * @param matchingEvent     the event to be matched with the events at the processor
     * @param compiledCondition execution element responsible for finding the corresponding events that matches
     *                          the matchingEvent based on pool of events at Processor
     * @return the matched events
     */

    @Override public synchronized StreamEvent find(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        if (compiledCondition instanceof Operator) {
            return ((Operator) compiledCondition).find(matchingEvent, expiredEventChunk, streamEventCloner);
        } else {
            return null;
        }
    }

    @Override public CompiledCondition compileCondition(Expression expression,
            MatchingMetaInfoHolder matchingMetaInfoHolder, SiddhiAppContext siddhiAppContext,
            List<VariableExpressionExecutor> variableExpressionExecutors, Map<String, Table> tableMap,
            String queryName) {
        return OperatorParser.constructOperator(expiredEventChunk, expression, matchingMetaInfoHolder, siddhiAppContext,
                variableExpressionExecutors, tableMap, this.queryName);
    }

    /**
     * This will be called only once and this can be used to acquire
     * required resources for the processing element.
     * This will be called after initializing the system and before
     * starting to process the events.
     */
    @Override public void start() {
        //Do nothing
    }

    /**
     * This will be called only once and this can be used to release
     * the acquired resources for processing.
     * This will be called before shutting down the system.
     */
    @Override public void stop() {
        //Do nothing
    }

    /**
     * Used to collect the serializable state of the processing element, that need to be
     * persisted for the reconstructing the element to the same state on a different point of time.
     *
     * @return stateful objects of the processing element as an map
     */
    @Override public Map<String, Object> currentState() {
        Map<String, Object> map = new HashMap<>();
        map.put("expiredEventchunck", expiredEventChunk.getFirst());
        map.put("map", this.map);
        return map;
    }

    /**
     * Used to restore serialized state of the processing element, for reconstructing
     * the element to the same state as if was on a previous point of time.
     *
     * @param map is the stateful objects of the element as an map on
     *            the same order provided by currentState().
     */
    @Override public void restoreState(Map<String, Object> map) {
        expiredEventChunk.clear();
        expiredEventChunk.add((StreamEvent) map.get("expiredEventchunck"));
        this.map = (ConcurrentHashMap) map.get("map");
    }

    /**
     * Used to generate key in map to get the old event for current event. It will map key which we give as unique
     * attribute with the event
     *
     * @param event the stream event that need to be processed
     */
    private String generateKey(StreamEvent event) {
        StringBuilder stringBuilder = new StringBuilder();
        for (VariableExpressionExecutor executor : variableExpressionExecutors) {
            stringBuilder.append(event.getAttribute(executor.getPosition()));
        }
        return stringBuilder.toString();
    }

}
