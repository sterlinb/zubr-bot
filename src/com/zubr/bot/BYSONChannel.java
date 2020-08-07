package com.zubr.bot;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens and operates a connection to a BYSON trading gate. This class parses information from raw messages as they arrive and passes it to a
 * {@link BYSONMessageHandler}, and has methods which assemble and transmit messages. This implementation handles only selected key message
 * types. Unhandled incoming messages are ignored (except for logging).
 */
public class BYSONChannel implements Closeable, AutoCloseable, BYSONRawListener {
	protected final InetSocketAddress target;
	protected final int login;
	protected final long account;
	
	private static final Logger logger = LoggerFactory.getLogger(BYSONChannel.class);
	
	protected static final byte[] SCHEMA_AND_VERSION = {0x04, 0x1c, 2, 0};
	protected static final long requestheartbeat = 5000000000l;
	
	private Socket connection;
	private long lastsend;
	private long sequence;
	private long request;
	
	private BYSONMessageHandler messagehandler;
	private OutChannelWorker outchannel;
	private InputChannelWorker inchannel;
	
	long heartbeat;
	
	/**
	 * Initializes the <code>BYSONChannel</code>. No connection is attempted until <code>connect()</code> is called.
	 * @param accessPoint address for the ZUBR BYSON protocol trading gate.
	 * @param loginid BYSON gate login id
	 * @param accountid BYSON gate account id
	 * @param firstrequest first request ID to use. Must be greater than any previously used request ID or messages will be rejected.
	 */
	public BYSONChannel(InetSocketAddress accessPoint, int loginid, long accountid, long firstrequest) {
		this.target = accessPoint;
		this.login = loginid;
		this.account = accountid;
		this.messagehandler = null;
		this.request = firstrequest;
	}
	
	/**
	 * Connects to the gate and establishes a session. 
	 * @throws IOException if a socket I/O error occurs or the session establishment is unsuccessful.
	 */
	public void connect() throws IOException {
		if(connection != null)
			return; // TODO error handling?
		
		//System.out.println("Connecting to " + target);
		logger.info("Connecting to {}", target);
		connection = new Socket(target.getAddress(), target.getPort());
		OutputStream out = connection.getOutputStream();
		logger.info("OutputStream opened");
		//System.out.println("OutputStream opened");
		lastsend = System.nanoTime();
		sendEstablish(out);
		
		InputStream in = connection.getInputStream();
		logger.trace("Waiting for initial acknowledgement");
		while(in.available() < 8) {
			//logger.trace("Holding for header, {} bytes so far", in.available());
			Thread.yield();
		}
		byte[] ack = new byte[24];
		int count = in.read(ack);
		
		logger.trace("Initial bytes read: {}", ack);
		if(ack[3] != (byte) 0x13 || ack[2] != (byte) 0x89) {
			//Did not get valid acknowledgement, abort;
			voidConnection();
			throw new IOException("Could not establish session, recieved " + Arrays.toString(ack));
		}
		
		// Collect full message
		while(count < ack[0] + 8) {
			int read = in.read(ack, count-1, ack[0] + 8 - count);
			if(read == -1) { //socket recieved end-of-file. Check necessary?
				voidConnection();
				return;
			}
				
			count += read;
		}
		
		
		heartbeat = SBEUtils.parse64(ack, 8);
		logger.info("Got heartbeat nanos: " + heartbeat);
		
		sequence = SBEUtils.parse64(ack, 16);
				
		outchannel = new OutChannelWorker(out, lastsend, heartbeat);
		new Thread(outchannel).start();
		inchannel = new InputChannelWorker(in);
		inchannel.addListener(this);
		new Thread(inchannel).start();
	}
	
	
	private void voidConnection() throws IOException {
		logger.info("Closing connection");
		if(outchannel != null)
			outchannel.stop();
		if(inchannel != null)
			inchannel.stop();
		try {
			connection.close();
		} finally {
			connection = null;
		}
	}
	
	protected void sendEstablish(OutputStream out) throws IOException {
		byte[] message = new byte[20];
		
		message[0] = (byte) 0x0C;
		message[1] = (byte) 0x00;
		message[2] = (byte) 0x88;
		message[3] = (byte) 0x13;
		System.arraycopy(SCHEMA_AND_VERSION, 0, message, 4, 4);
		SBEUtils.write64(message, requestheartbeat, 8);;
		SBEUtils.write32(message, login, 16);
		
		logger.info("Sending message {}", message);
		out.write(message);
		out.flush();
	}

	/**
	 * Stops communication and closes the connection. Does not perform any session wrap-up. May prevent transmission of messages currently
	 * in internal working queue.
	 * @throws IOException if an I/O error occurs when closing the underlying socket.
	 */
	@Override
	public void close() throws IOException {
		voidConnection();
	}
	
	protected boolean isSession (int messagetype) {
		return messagetype <= 5999 && messagetype >= 5000;
	}
	
	/**
	 * Receives BYSON messages. Not intended for external use, used to receive messages from an internally created {@code InputChannelWorker}.
	 */
	@Override
	public void messageRecieved(byte[] message) {
		logger.trace("Message recieved: {}", message);
		int type = SBEUtils.parse16(message, 2);
		
		long locseq;
		BYSONMessageHandler handler;
		synchronized(this) {
			if(!isSession(type))
				if(message.length >= 16 && SBEUtils.parse64(message, 8) != sequence) {
					// relies on the fact that all existing application messages have SeqNo first.
					logger.warn("Sequencing problem, recieved {} expected {}", SBEUtils.parse64(message, 8), sequence);
					// TODO: handle sequencing failure?
				} else if (message.length < 16)
					logger.warn("Anomalous message, type {} with length {}", type, message.length);
				else
					sequence++;

			locseq = sequence;
			handler = this.getMessageHandler();
		}
		
		switch (type) {
		case 7000: // NewOrderSingleReport Successful order placement
			if(handler == null)
				logger.warn("No handler for order placement message");
			else
				handler.handleNewOrderSingleReport(SBEUtils.parse64(message, 52), SBEUtils.parse64(message, 60),
						SBEUtils.parse32(message, 68), message[74]==1, SBEUtils.parse64(message, 24), SBEUtils.parse64(message, 32));
			break;
		case 7001: // NewOrderReject, should not happen! But if it does:
			if(handler == null)
				logger.warn("No handler for order rejection message");
			else
				handler.handleNewOrderReject(SBEUtils.parse64(message, 24), message[32]);
			break;
		case 7008: // ExecutionReport
			if(handler == null)
				logger.warn("No handler for execution report message");
			else
				handler.handleExecutionReport(SBEUtils.parse64(message, 52), SBEUtils.parse64(message, 40), SBEUtils.parse32(message, 48),
						SBEUtils.parse32(message, 60), SBEUtils.parse64(message, 24));
			break;
		case 7004: // OrderReplaceReport, order update successful
			if(handler == null)
				logger.warn("No handler for order replacement message");
			else
				handler.handleOrderReplaceReport(SBEUtils.parse64(message, 40), SBEUtils.parse64(message, 48), SBEUtils.parse32(message, 56),
						SBEUtils.parse64(message, 24), SBEUtils.parse64(message, 60), SBEUtils.parse64(message, 32));
			break;
		case 7005: // OrderReplaceReject, order update unsuccessful, must re-order.
			if(handler == null)
				logger.warn("No handler for order replacement rejection message");
			else
				handler.handleOrderReplaceReject(SBEUtils.parse64(message, 24), message[32]);
			break;
		case 5003: // Terminate. Preferably to be avoided rather than handled.
			if(handler == null)
				logger.error("Session terminated with code {}, no handler", message[8]);
			else
				handler.handleTerminate(message[8]);
			break;
		case 5007: // Sequence.
			if(locseq != SBEUtils.parse64(message, 8))
				logger.warn("Hearbeat sequence number {} disagrees with internal {}", SBEUtils.parse64(message, 8), locseq);
			break;
		case 5008: // FloodReject, if this ever happens it is a serious problem.
			if(handler == null)
				logger.warn("No handler for flooding message");
			else
				handler.handleFloodReject(SBEUtils.parse64(message, 8), SBEUtils.parse64(message, 20));
			break;
		case 5009: // MessageReject, should never happen when debugging is done.
			if(handler == null)
				logger.error("Message rejected, no hander: code {} message id {} problem field {}", message[20],
						SBEUtils.parse64(message, 8), SBEUtils.parse32(message, 16));
			else
				handler.handleMessageReject(SBEUtils.parse64(message, 8), message[20], SBEUtils.parse32(message, 16));
			break;
		default:
			logger.debug("Unhandled message recieved, id {}", type);
			break;
		}
	}
	
	/**
	 * Sends a limit order placement message.
	 * @param price the price for the order.
	 * @param size the size of the order.
	 * @param buy {@code true} for buy order, {@code false} for sell order 
	 * @param instrument the instrument ID for the order
	 * @return the requestID of the message sent
	 */
	public long sendNewOrderSingle(long price, int size, boolean buy, int instrument) {
		long req;
		synchronized(this) { req = request++; }
		byte[] message = new byte[51];
		message[0] = 43;
		SBEUtils.write16(message, 6001, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, message, 4, 4);
		SBEUtils.write64(message, -1, 8); // TraceID
		SBEUtils.write64(message, req, 16); // request ID
		SBEUtils.write64(message, account, 24);
		SBEUtils.write32(message, instrument, 32); // instrument ID
		SBEUtils.write64(message, price, 36);
		SBEUtils.write32(message, size, 44);
		message[48] = 1; // Order type: limit;
		message[49] = 1; // Time in force: good till canceled
		message[50] = (byte) (buy?1:2);
		
		outchannel.enqueue(message);
		
		return req;
	}
	
	/**
	 * Sends a order replacement message.
	 * @param orderid the ID of the existing order
	 * @param price the new price
	 * @param size the new size
	 * @return the requestID of the message sent
	 */
	public long sendOrderReplaceRequest(long orderid, long price, int size) {
		long req;
		synchronized(this) { req = request++; }
		byte[] message = new byte[46];
		message[0] = (byte) (message.length - 8);
		SBEUtils.write16(message, 6003, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, message, 4, 4);
		SBEUtils.write64(message, -1, 8); // TraceID
		SBEUtils.write64(message, req, 16); // request ID
		SBEUtils.write64(message, orderid, 24);
		SBEUtils.write64(message, price, 32);
		SBEUtils.write32(message, size, 40);
		message[44] = -1; // Null for order type
		message[45] = -1; // null for time in force
		
		outchannel.enqueue(message);
		
		return req;
	}
	
	/**
	 * Sends a mass order cancellation request. Note that the current implementation will not process replies to this message type!
	 * <p>
	 * It is preferred that this message be used sparingly.
	 * @param instrument the instrument to cancel orders on. -1 should cancel across all instruments.
	 * @param side the side to cancel orders on. 1 for buy orders, 2 for sell orders, -1 for both.
	 * @return the requestID of the message sent.
	 */
	public long sendOrderMassCancelRequest(int instrument, byte side) {
		long req;
		synchronized(this) { req = request++; }
		byte[] message = new byte[37];
		message[0] = (byte) (message.length - 8);
		SBEUtils.write16(message, 6004, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, message, 4, 4);
		SBEUtils.write64(message, -1, 8); // TraceID
		SBEUtils.write64(message, req, 16); // request ID
		SBEUtils.write64(message, account, 24);
		SBEUtils.write32(message, instrument, 32); // instrument ID
		if(side != 1 && side != 2)
			side = -1;
		message[36] = side;
		
		outchannel.enqueue(message);
		
		return req;
	}
	
	public synchronized BYSONMessageHandler getMessageHandler() {
		return this.messagehandler;
	}
	
	public synchronized void setMessageHandler(BYSONMessageHandler handler) {
		this.messagehandler = handler;
	}

	/**
	 * Standalone test launch method.
	 * @param args first three must be BYSON ip address, login ID, and account ID.
	 */
	public static void main(String[] args) {
		// Independent testing code.
		try {
			BYSONChannel channel = new BYSONChannel(new InetSocketAddress(InetAddress.getByName(args[0]), 12345), Integer.parseUnsignedInt(args[1]),
					Long.parseUnsignedLong(args[2]), 1L);
			channel.connect();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}


}
