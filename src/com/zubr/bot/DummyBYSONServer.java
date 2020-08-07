package com.zubr.bot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dummy BYSON server. This provides bare-minimum positive responses to session establishment and order placement and change requests
 * and produces limited logs.
 * <p>
 * A very inadequate test environment, but can allow a simple trading bot to run without connecting to the real ZUBR servers.
 */
public class DummyBYSONServer {
	private static final Logger logger = LoggerFactory.getLogger(DummyBYSONServer.class);
	protected static final byte[] SCHEMA_AND_VERSION = {0x04, 0x1c, 2, 0};

	/**
	 * Runs the server.
	 * @param args unused.
	 */
	public static void main(String[] args) {
		Socket socket = null;
		InputStream in = null;
		OutputStream out = null;
		try (ServerSocket ssoc = new ServerSocket(12345);) {
			socket = ssoc.accept();
			in = socket.getInputStream();
			out = socket.getOutputStream();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		byte[][] message = null;
		try {
			message = readMessage(in);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		int code = SBEUtils.parse16(message[0], 2);
		if(code != 5000) {
			logger.info("Session opened with non-Establish message: {}", (Object[]) message);
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				System.exit(1);
			}
		}
		
		long nextseq = 1;
		long nextorder = 1;
		HashSet<Long> orders = new HashSet<>();
		
		byte[] reply = new byte[24];
		SBEUtils.write16(reply, 16, 0);
		SBEUtils.write16(reply, 5001, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, reply, 4, 4);
		System.arraycopy(message[1], 0, reply, 8, 8);
		logger.info("sending ack");
		SBEUtils.write64(reply, nextseq, 16);
		try {
			out.write(reply);
			out.flush();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		reply = null;
		
		while(true) {
			message = null;
			try {
				message = readMessage(in);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
			code = SBEUtils.parse16(message[0], 2);
			
			try {
				switch(code) {
				case 5007:
					logger.info("Received sequence message");
					break;
				case 6001:
					logger.info("Received new order message");
					orders.add(nextorder);
					out.write(orderApproval(message, nextseq++, nextorder++));
					out.flush();
					break;
				case 6003:
					if(orders.contains(SBEUtils.parse64(message[1], 16))) {
						logger.info("Valid order change request {}", SBEUtils.parse64(message[1], 16));
						orders.remove(SBEUtils.parse64(message[1], 16));
					} else {
						logger.info("Invalid order change request");
					}
					orders.add(nextorder);
					out.write(changeApproval(message, nextseq++, nextorder++));
					out.flush();
					break;
				}
			} catch(IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
	
	private static byte[] changeApproval(byte[][] message, long seq, long id) {
		byte[] reply = new byte[68];
		SBEUtils.write16(reply, reply.length-8, 0);
		SBEUtils.write16(reply, 7004, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, reply, 4, 4);
		SBEUtils.write64(reply, seq, 8);
		System.arraycopy(message[1], 0, reply, 16, 16);
		SBEUtils.write64(reply, System.nanoTime(), 32);  // timestamp
		SBEUtils.write64(reply, id, 40); // new orderid
		System.arraycopy(message[1], 24, reply, 48, 12);
		System.arraycopy(message[1], 16, reply, 60, 8);
		
		return reply;
	}

	private static byte[] orderApproval(byte[][] message, long seq, long id) {
		byte[] reply = new byte[75];
		SBEUtils.write16(reply, reply.length-8, 0);
		SBEUtils.write16(reply, 7000, 2);
		System.arraycopy(SCHEMA_AND_VERSION, 0, reply, 4, 4);
		SBEUtils.write64(reply, seq, 8);
		System.arraycopy(message[1], 0, reply, 16, 16);
		SBEUtils.write64(reply, System.nanoTime(), 32);
		System.arraycopy(message[1], 16, reply, 40, 12);
		SBEUtils.write64(reply, id, 52);
		System.arraycopy(message[1], 28, reply, 60, 15);
		
		return reply;
	}

	private static byte[][] readMessage(InputStream in) throws IOException{
		byte[][] ret = new byte[2][];
		ret[0] = new byte[8];
		int count = in.read(ret[0]);
		while(count < 8) {
			int next = in.read(ret[0], count, 8-count);
			if(next == -1)
				throw new IOException("Channel ended in header");
			else
				count += next;
		}
		
		ret[1] = new byte[ret[0][0] + 256*ret[0][1]];
		count = in.read(ret[1]);
		while(count < ret[1].length) {
			int next = in.read(ret[1], count, ret[1].length-count);
			if(next == -1)
				throw new IOException("Channel ended in message body");
			else
				count += next;
		}
		
		return ret;
	}

}
