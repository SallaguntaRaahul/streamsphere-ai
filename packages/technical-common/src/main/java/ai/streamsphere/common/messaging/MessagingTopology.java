package ai.streamsphere.common.messaging;

public final class MessagingTopology {
    public static final String EVENTS_EXCHANGE = "streamsphere.events";
    public static final String DLX = "streamsphere.dlx";
    private MessagingTopology() {}

    public static String queue(String service) { return service + ".events"; }
    public static String retryQueue(String service) { return service + ".retry"; }
    public static String dlq(String service) { return service + ".dlq"; }
}
