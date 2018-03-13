/**
 * All rights Reserved, Designed By Suixingpay.
 *
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月24日 11:19
 * @Copyright ©2017 Suixingpay. All rights reserved.
 * 注意：本内容仅限于随行付支付有限公司内部传阅，禁止外泄以及用于其他的商业用途。
 */
package com.suixingpay.datas.node.task.load;

import com.suixingpay.datas.common.cluster.ClusterProviderProxy;
import com.suixingpay.datas.common.cluster.command.TaskPositionUploadCommand;
import com.suixingpay.datas.common.cluster.data.DTaskStat;
import com.suixingpay.datas.common.exception.TaskStopTriggerException;
import com.suixingpay.datas.common.statistics.NodeLog;
import com.suixingpay.datas.node.core.event.etl.ETLBucket;
import com.suixingpay.datas.node.core.event.s.EventType;
import com.suixingpay.datas.node.core.loader.DataLoader;
import com.suixingpay.datas.node.core.loader.SubmitStatObject;
import com.suixingpay.datas.node.core.task.AbstractStageJob;
import com.suixingpay.datas.node.core.task.StageType;
import com.suixingpay.datas.node.task.worker.TaskWork;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Date;
import java.util.List;

/**
 * 完成SQL事件的最终执行，单线程执行,通过interrupt终止线程
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月24日 11:19
 * @version: V1.0
 * @review: zhangkewei[zhang_kw@suixingpay.com]/2017年12月24日 11:19
 */
public class LoadJob extends AbstractStageJob {
    private final DataLoader dataLoder;
    private final TaskWork work;
    public LoadJob(TaskWork work) {
        super(work.getBasicThreadName());
        this.dataLoder = work.getDataLoader();
        this.work = work;
    }

    @Override
    protected void doStop() {
        try {
            dataLoder.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void doStart() throws Exception {
        dataLoder.startup();
    }

    @Override
    protected void loopLogic() {
        //只要队列有消息，持续读取
        ETLBucket bucket = null;
        do {
            try {
                bucket = work.waitEvent(StageType.TRANSFORM);
                //异常
                if (null != bucket && null != bucket.getException()) {
                    throw new TaskStopTriggerException(bucket.getException());
                }

                //没有异常
                if (null != bucket && null == bucket.getException()) {
                    //执行载入逻辑
                    Pair<Boolean, List<SubmitStatObject>> loadResult = dataLoder.load(bucket);
                    //逻辑执行失败
                    if (!loadResult.getLeft()) throw new TaskStopTriggerException("批次" + bucket.getSequence() + "Load失败!");
                    //批次数据不为空
                    if (!bucket.getRows().isEmpty()) {
                        String position = bucket.getRows().get(bucket.getRows().size() - 1).getPosition();
                        LOGGER.debug("开始提交消费同步点:{}", position);
                        ClusterProviderProxy.INSTANCE.broadcast(new TaskPositionUploadCommand(work.getTaskId(),
                                work.getDataConsumer().getSwimlaneId(), position));
                        LOGGER.debug("提交消费同步点到集群策略:{}", position);
                        work.getDataConsumer().commitPosition(position);
                        LOGGER.debug("提交消费同步点到消费器客户端:{}", position);
                    }
                    //更新消费统计数据
                    loadResult.getRight().forEach(o -> updateStat(o));
                }
            } catch (TaskStopTriggerException stopException) {
                LOGGER.error("Load ETLRow error", stopException);
                stopException.printStackTrace();
                work.stopAndAlarm(stopException.getMessage());
            } catch (Exception e) {
                NodeLog.upload(NodeLog.LogType.TASK_LOG, work.getTaskId(), work.getDataConsumer().getSwimlaneId(),
                        "Load ETLRow error"  + e.getMessage());
                LOGGER.error("Load ETLRow error!", e);
            }
        } while (null != bucket);
    }
    @Override
    public ETLBucket output() throws Exception {
        throw new Exception("unsupported Method");
    }

    @Override
    public boolean isPrevPoolEmpty() {
        return work.isPoolEmpty(StageType.TRANSFORM);
    }

    @Override
    public boolean stopWaiting() {
        return work.getDataConsumer().isAutoCommitPosition();
    }


    /**
     * 更新任务状态
     *  For a prepared statement batch, it is not possible to know the number of rows affected in the database
     *  by each individual statement in the batch.Therefore, all array elements have a value of -2.
     *  According to the JDBC 2.0 specification, a value of -2 indicates that the operation was successful
     *  but the number of rows affected is unknown.
     * @param object
     */
    private void updateStat(SubmitStatObject object) {
        //虽然每个状态值的变更都有stat对象锁，但在最外层加对象锁避免了多次请求的问题（锁可重入），同时保证状态各字段变更一致性
        DTaskStat stat = work.getDTaskStat(object.getSchema(), object.getTable());
        synchronized (stat) {
            int affect = object.getAffect();
            EventType eventType = object.getType();
            switch (eventType.getIndex()) {
                case EventType.DELETE_INDEX:
                    if (affect > 0 || affect == -2) {
                        stat.incrementDeleteRow();
                    } else {
                        stat.incrementErrorDeleteRow();
                    }
                    break;
                case EventType.UPDATE_INDEX:
                    if (affect > 0 || affect == -2) {
                        stat.incrementUpdateRow();
                    } else {
                        stat.incrementErrorUpdateRow();
                    }
                    break;
                case EventType.INSERT_INDEX:
                    if (affect > 0 || affect == -2) {
                        stat.incrementInsertRow();
                    } else {
                        stat.incrementErrorInsertRow();
                    }
                    break;
                case EventType.TRUNCATE_INDEX:
                    if (affect > 0 || affect == -2) {
                        stat.incrementDeleteRow();
                    } else {
                        stat.incrementErrorDeleteRow();
                    }
                    break;
            }

            //更新最后执行消息事件的产生时间，用于计算从消息产生到加载如路时间、计算数据同步检查时间
            if (null != object.getOpTime()) stat.setLastLoadedDataTime(object.getOpTime());
            stat.setLastLoadedSystemTime(new Date());
            if (!StringUtils.isBlank(object.getPosition())) {
                stat.setProgress(object.getPosition());
            }
        }
    }
}
