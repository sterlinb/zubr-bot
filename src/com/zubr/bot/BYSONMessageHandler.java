package com.zubr.bot;

/**
 * Interface to receive BYSON messages processed by a {@link BYSONChannel}. Calls correspond to specific BYSON protocol messages.
 * <p>
 * For more information, see <a href="https://spec.zubr.io/#byson-trading-protocol">the BYSON protocol specification.</a>
 */
public interface BYSONMessageHandler {
	/**
	 * Called when a report arrives that an order has been executed.
	 * <p>
	 * If {@code remain > 0} the remnant order will still be open. If {@code remain == 0} it will have been purged by the server.
	 * @param orderid ID of the order executed.
	 * @param price price point of the execution.
	 * @param size size of the fill.
	 * @param remain remaining amount of the order.
	 * @param timestamp market timestamp of the transaction.
	 */
	public void handleExecutionReport(long orderid, long price, int size, int remain, long timestamp);
	
	/**
	 * Called when a report arrives that an order has been placed.
	 * @param orderid ID of the new order.
	 * @param price price point of the new order.
	 * @param size size of the new order.
	 * @param buy {@code true} for buy orders, {@code false} for sell orders.
	 * @param requestid requestID of the request to place the order.
	 * @param timestamp market timestamp of the placement.
	 */
	public void handleNewOrderSingleReport(long orderid, long price, int size, boolean buy, long requestid, long timestamp);
	
	/**
	 * Called when a report arrives that an order has been replaced.
	 * @param orderid ID of the new order.
	 * @param price price point of the new order.
	 * @param size size of the new order.
	 * @param requestid requestID of the request to place the order.
	 * @param oldorderid ID of the old order.
	 * @param timestamp market timestamp of the replacement.
	 */
	public void handleOrderReplaceReport(long orderid, long price, int size, long requestid, long oldorderid, long timestamp);
	
	/**
	 * Called when a report arrives that an order placement has been rejected.
	 * @param requestid requestID of the request to place the order.
	 * @param reason BYSON protocol OrderRejectReason for the rejection.
	 */
	public void handleNewOrderReject(long requestid, byte reason);
	
	/**
	 * Called when a report arrives that an order replacement has been rejected.
	 * @param requestid requestID of the request to replace the order.
	 * @param reason BYSON protocol OrderRejectReason for the rejection.
	 */
	public void handleOrderReplaceReject(long requestid, byte reason);
	
	/**
	 * Called when a report arrives that the session has been blocked due to too many messages.
	 * @param requestid requestID of the request which triggered the lock.
	 * @param timeout penalty period for which messages are not allowed, in nanoseconds.
	 */
	public void handleFloodReject(long requestid, long timeout);
	
	/**
	 * Called when a message arrives terminating the session.
	 * @param reason BYSON protocol TerminationCode.
	 */
	public void handleTerminate(byte reason);

	/**
	 * Called when a message arrives that a message was rejected. Appears to be used when messages are rejected for reasons not specific to
	 * their type.
	 * @param requestid requestID of the request that was rejected.
	 * @param reason BYSON protocol MessageRejectReason for the rejection. 
	 * @param fieldid code identifying the defective field of the request, if applicable.
	 */
	public void handleMessageReject(long requestid, byte reason, int fieldid);
}
