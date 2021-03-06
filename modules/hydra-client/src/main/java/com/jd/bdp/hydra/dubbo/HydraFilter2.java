/**
 * 
 */
package com.jd.bdp.hydra.dubbo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.jd.bdp.hydra.BinaryAnnotation;
import com.jd.bdp.hydra.Endpoint;
import com.jd.bdp.hydra.Span;
import com.jd.bdp.hydra.agent.Tracer;
import com.jd.bdp.hydra.agent.support.TracerUtils;

/**
 * 这个filter兼容dubbox提供的 restful服务，因rest服务被html5调用，所以只有 服务端接收请求与服务端发送响应 两个阶段
 * 
 * @author Administrator
 * 
 */
@Activate(group = { Constants.PROVIDER, Constants.CONSUMER })
public class HydraFilter2 implements Filter {

    private static Logger logger = LoggerFactory.getLogger(HydraFilter2.class);

    private Tracer tracer = null;

    // setter
    public void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    // 调用过程拦截
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if(tracer==null){ //空指针处理
            logger.debug("tracer is null,this request doesnot be traced by hydra filer");
            return invoker.invoke(invocation);
        }
        // 异步获取serviceId，没获取到不进行采样
        String serviceId = tracer.getServiceId(RpcContext.getContext().getUrl().getServiceInterface());
        if (serviceId == null) {
            Tracer.startTraceWork();
            return invoker.invoke(invocation);
        }

        long start = System.currentTimeMillis();
        RpcContext context = RpcContext.getContext();
        boolean isConsumerSide = context.isConsumerSide();
        Span span = null;
        Endpoint endpoint = null;
        Result result = null;
        try {
            endpoint = tracer.newEndPoint();
            // endpoint.setServiceName(serviceId);
            endpoint.setIp(context.getLocalAddressString());
            endpoint.setPort(context.getLocalPort());
            if (context.isConsumerSide()) { // 是否是消费者
                Span span1 = tracer.getParentSpan();
                if (span1 == null) { // 为rootSpan
                    span = tracer.newSpan(context.getMethodName(), endpoint, serviceId);// 生成root
                                                                                        // Span

                } else {
                    span = tracer.genSpan(span1.getTraceId(), span1.getId(), tracer.genSpanId(),
                            context.getMethodName(), span1.isSample(), serviceId);
                }
            } else if (context.isProviderSide()) { // 服务端
                Long traceId, parentId, spanId;
                traceId = TracerUtils.getAttachmentLong(invocation.getAttachment(TracerUtils.TID));
                parentId = TracerUtils.getAttachmentLong(invocation.getAttachment(TracerUtils.PID));
                spanId = TracerUtils.getAttachmentLong(invocation.getAttachment(TracerUtils.SID));
                /*
                 * 为了兼容dubbox restful服务，增加相应的逻辑
                 */

                if (RpcContext.getContext().getUrl().getProtocol().startsWith("rest")) {
                    if (traceId == null) {
                        traceId = tracer.genTracerId();
                    }
                    if (spanId == null) {
                        spanId = tracer.genSpanId();
                    }
                    /*
                     * if (parentId == null) { parentId = spanId; }
                     */
                    boolean isSample = (traceId != null);
                    span = tracer.genSpan(traceId, parentId, spanId, context.getMethodName(), isSample, serviceId); // rest服务生成的
                                                                                                                    // span
                } else {
                    boolean isSample = (traceId != null);
                    span = tracer.genSpan(traceId, parentId, spanId, context.getMethodName(), isSample, serviceId);
                }

            }
            invokerBefore(invocation, span, endpoint, start);// 记录annotation
            RpcInvocation invocation1 = (RpcInvocation) invocation;
            setAttachment(span, invocation1);// 设置需要向下游传递的参数
            result = invoker.invoke(invocation);
            if (result.getException() != null) {
                catchException(result.getException(), endpoint);
            }
            return result;
        } catch (RpcException e) {
            if (e.getCause() != null && e.getCause() instanceof TimeoutException) {
                catchTimeoutException(e, endpoint);
            } else {
                catchException(e, endpoint);
            }
            throw e;
        } finally {
            if (span != null) {
                long end = System.currentTimeMillis();
                invokerAfter(invocation, endpoint, span, end, isConsumerSide);// 调用后记录annotation
            }
        }
    }

    private void catchTimeoutException(RpcException e, Endpoint endpoint) {
        BinaryAnnotation exAnnotation = new BinaryAnnotation();
        exAnnotation.setKey(TracerUtils.EXCEPTION);
        exAnnotation.setValue(e.getMessage());
        exAnnotation.setType("exTimeout");
        exAnnotation.setHost(endpoint);
        tracer.addBinaryAnntation(exAnnotation);
    }

    private void catchException(Throwable e, Endpoint endpoint) {
        BinaryAnnotation exAnnotation = new BinaryAnnotation();
        exAnnotation.setKey(TracerUtils.EXCEPTION);
        exAnnotation.setValue(e.getMessage());
        exAnnotation.setType("ex");
        exAnnotation.setHost(endpoint);
        tracer.addBinaryAnntation(exAnnotation);
    }

    private void setAttachment(Span span, RpcInvocation invocation) {
        if (span.isSample()) {
            invocation.setAttachment(TracerUtils.PID, span.getParentId() != null ? String.valueOf(span.getParentId())
                    : null);
            invocation.setAttachment(TracerUtils.SID, span.getId() != null ? String.valueOf(span.getId()) : null);
            invocation.setAttachment(TracerUtils.TID, span.getTraceId() != null ? String.valueOf(span.getTraceId())
                    : null);
        }
    }

    private void invokerAfter(Invocation invocation, Endpoint endpoint, Span span, long end, boolean isConsumerSide) {
        if (isConsumerSide && span.isSample()) {
            tracer.clientReceiveRecord(span, endpoint, end, invocation.toString()); // 客户端接收记录
        } else {
            if (span.isSample()) {
                tracer.serverSendRecord(span, endpoint, end, invocation.toString()); // 服务端发送记录
            }
            tracer.removeParentSpan();
        }
    }

    private void invokerBefore(Invocation invocation, Span span, Endpoint endpoint, long start) {
        RpcContext context = RpcContext.getContext();
        RpcInvocation invocation1 = (RpcInvocation) invocation;
        if (context.isConsumerSide() && span.isSample()) {
            tracer.clientSendRecord(span, endpoint, start, invocation1.toString()); // 客户端发送记录
        } else if (context.isProviderSide()) {
            if (span.isSample()) {
                tracer.serverReceiveRecord(span, endpoint, start, invocation1.toString()); // 服务端接收记录
            }
            tracer.setParentSpan(span);
        }
    }

    /* 加载Filter的时候加载hydra配置上下文 */
    /**/
   /* static {
        logger.info("Hydra filter is loading hydra-config file...");
        String resourceName = "classpath*:META-INF/spring/hydra-config.xml";
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(resourceName.split("[,\\s]+"));
   
        logger.info("Hydra config context is starting,config file path is:{}" , resourceName);
        context.start();
    }*/

}