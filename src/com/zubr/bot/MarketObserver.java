package com.zubr.bot;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * A WebSocket client that subscribes the the ZUBR orderbook datastream, tracks the current state of orders for a specific instrument, and
 * notifies a {@link OrderBookListener} whenever the orders update.  
 * @author Bram Sterling
 * @see OrderBookListener
 */
public class MarketObserver extends WebSocketClient{
	private static final Logger logger = LoggerFactory.getLogger(MarketObserver.class);
	private final int instrument;
	private final String instrumentstr;
	
	private TreeMap<Long, Integer> bids, asks;
	
	private OrderBookListener listener;
	private int listenerdepth;

	/**
	 * Creates a {@code MarketObserver} set to connect to the ZUBR WebSocket interface at the specified URI and observe the orderbook for the specified instrument.
	 * The observer does not connect automatically. The connection will be established once you call {@code connect}.
	 * @param serverUri the URI of the ZUBR WebSocket API access point.
	 * @param instrument The instrument ID to be observed.
	 */
	public MarketObserver(URI serverUri, int instrument) {
		super(serverUri);
		this.addHeader("User-Agent", "TradeRobot");
		this.instrument = instrument;
		this.instrumentstr = Integer.toString(instrument);
	}

	/**
	 * Called after the websocket connection has been closed. Not for external use!
	 */
	@Override
	public void onClose(int code, String reason, boolean remote) {
		logger.info("Websocket closed: code {}, {} remote:{}", code, reason, remote);
	}

	@Override
	public void onError(Exception ex) {
		logger.error("Websocket error: {}", ex);
	}

	/**
	 * Callback for string messages received from the remote host. Not for external use!
	 * <p>
	 * Identifies order book messages, uses them to update the internal order book, and notifies the listener.
	 */
	@Override
	public void onMessage(String recieved) {
		
		JsonObject message = Json.parse(recieved).asObject();
		
		if(message.get("id") == null) {
			// This is a subscription message.
			JsonObject result = message.get("result").asObject();
			String channel = result.getString("channel", null);
			
			if(channel.equals("orderbook")) {
				JsonObject insdata = (JsonObject) result.get("data").asObject().get("value").asObject().get(instrumentstr);
				if(insdata != null) {
					OrderBookListener toalert = null;
					OrderBookEntry[] bidlist, asklist;
					logger.trace("Updating order book");
					synchronized(this) {
						if(insdata.getBoolean("isSnapshot", false)) {
							bids = new TreeMap<>((a, b) -> b.compareTo(a));
							asks = new TreeMap<>();
						}

						for(JsonValue order:insdata.get("bids").asArray()) {
							long price = order.asObject().get("price").asObject().get("mantissa").asLong();
							int ex = order.asObject().get("price").asObject().getInt("exponent", -9) + 9;
							while(ex > 0) {
								price *= 10;
								ex--;
							}
							while(ex < 0) {
								price /= 10; // Can lose precision, but only if orders can be priced more finely than increments of 10^-9.
								ex++;
							}
							int quantity = order.asObject().get("size").asInt();

							if(quantity > 0)
								bids.put(price, quantity);
							else
								bids.remove(price);
						}

						for(JsonValue order:insdata.get("asks").asArray()) {
							long price = order.asObject().get("price").asObject().get("mantissa").asLong();
							int ex = order.asObject().get("price").asObject().getInt("exponent", -9) + 9;
							while(ex > 0) {
								price *= 10;
								ex--;
							}
							while(ex < 0) {
								price /= 10; // Can lose precision, but only comes up if exponent can be less than -9.
								ex++;
							}
							int quantity = order.asObject().get("size").asInt();

							if(quantity > 0)
								asks.put(price, quantity);
							else
								asks.remove(price);
						}
						//Updates done
						
						
						int depth = this.getDepth();
						bidlist = extractTop(bids, depth, true);
						asklist = extractTop(asks, depth, false);
						toalert = this.getListener();
					}
					
					if(toalert != null)
						toalert.bookUpdate(bidlist, asklist);
				}
			} // Add branches to handle other channels as desired.
		} else {
			// Handle reply messages. Currently no handling needed.
		}
	}
	
	private OrderBookEntry[] extractTop(TreeMap<Long, Integer> map, int depth, boolean bid) {
		OrderBookEntry[] ret = new OrderBookEntry[depth];
		Iterator<Entry<Long, Integer>> itr = map.entrySet().iterator();
		for(int i = 0; i < depth && itr.hasNext(); i++) {
			Entry<Long, Integer> next = itr.next();
			ret[i] = new OrderBookEntry(instrument, next.getKey(), next.getValue(), bid);
		}
		return ret;
	}

	/**
	 * Called after an opening handshake has been performed and the given websocket is ready to be written on. Not for external use!
	 */
	@Override
	public void onOpen(ServerHandshake handshake) {
		logger.debug("Websocket opened with handshake: {}", handshake.getHttpStatusMessage());
		
		// The API demands a ping every 15 seconds. The period is set to 14 seconds instead to make sure delay overruns don't cause timeout.
		Timer heartbeat = new Timer(true);
		heartbeat.schedule(new TimerTask() {
			@Override
			public void run() {
				sendPing();
				logger.trace("Ping frame sent");
			}
		}, 14000l, 14000l);
		
		// Subscribe to the orderbook channel to get needed market information.
		JsonObject sub = new JsonObject();
		sub.add("method", 1).add("params", new JsonObject().add("channel", "orderbook")).add("id", 1);
		this.send(sub.toString());
		
		
	}

	private synchronized OrderBookListener getListener() {
		return listener;
	}
	
	private synchronized int getDepth() {
		return listenerdepth;
	}

	/**
	 * Sets the {@code OrderBookListener} to be notified when the orders update. The listener will receive arrays of {@link OrderBookEntry}
	 * with length equal to {@code depth}. If the book is small, excess entries (potentially all entries) will be {@code null}.
	 * <p>
	 * Note the implementation only supports a single listener. If a listener has previously been assigned it will be replaced. May be
	 * called with {@code null} to deregister the listener.
	 * @param listener the {@code OrderBookListener} that will receive notifications. 
	 * @param depth the number of entries that will be provided to {@code listener} from each of the books. Must be positive, unless
	 * 		{@code listener} is {@code null}.
	 * @throws IllegalArgumentException if {@code depth <= 0} and {@code listener != null}.
	 */
	public synchronized void setListener(OrderBookListener listener, int depth) {
		if(depth <= 0 && listener != null)
			throw new IllegalArgumentException("MarketObserver listener depth must be positive");
		this.listener = listener;
		listenerdepth = Math.max(depth, 0);
	}

	/**
	 * Test code to instantiate a MarketObserver. Monitors instrument 2, arbitrarily.
	 * 
	 * @param args First command line argument must contain json configuration file with a string property "endpoint" containing target
	 * websocket URI.
	 */
	public static void main(String[] args) {
		logger.info("Loading configuration");
		JsonObject config = null;
		try(FileReader confile = new FileReader(args[0])) {
			config = Json.parse(confile).asObject();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}

		String endpoint = config.get("endpoint").asString();
		logger.info("Constructing Websocket client to {}", endpoint);
		MarketObserver observer = null;
		try {
			observer = new MarketObserver(new URI(endpoint), 2);
		} catch (URISyntaxException e) {
			logger.error("Invalid endpoint URI");
			System.exit(1);
		}
		logger.info("Connecting websocket");
		observer.connect();
		OrderBookListener listener = new OrderBookListener() {
			private volatile long marketbid = Long.MAX_VALUE, marketask = 0;
			{
				new Timer(true).schedule(new TimerTask() {
					@Override
					public void run() {
						logger.info("Market bid: {} Market ask: {}", marketbid, marketask);
					}
				}, 1000, 60000);
			}
			
			@Override
			public void bookUpdate(OrderBookEntry[] bids, OrderBookEntry[] asks) {
				long newmarketbid = (bids[0] == null)?Long.MAX_VALUE:bids[0].getPrice();
				long newmarketask = (asks[0] == null)?0:asks[0].getPrice();
				if(newmarketask != marketask || newmarketbid != marketbid)
					logger.trace("Top of book changed");
				marketbid = newmarketbid;
				marketask = newmarketask;
			}
		};
		observer.setListener(listener, 1);
	}
}
