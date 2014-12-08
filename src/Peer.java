/*
 * @author Gurpreet Pannu, Michael Norris, Priyam Patel
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class Peer extends Thread{

	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(Peer.class.getName());
	
	private static final String[] message_types = {
		"KEEP_ALIVE", "CHOKE", "UNCHOKE", "INTERESTED", "NOT_INTERESTED",
		"HAVE", "BITFIELD", "REQUEST", "PIECE", "CANCEL"
	};
	
	/**
	 *  The client.
	 */
	RUBTClient client;
	
	/**
	 *  Indicate if client is choking the peer (initially true)
	 */
	boolean amChoking = true;
	
	/**
	 *  Indicate if the client is interested in the peer (initially false)
	 */
	boolean amInterested = false;
	
	/**
	 *  Indicate if the peer is choking the client (initially true)
	 */
	boolean peerChoking = true;
	
	/**
	 *  Indicate if the peer is interested in the client (initially false)
	 */
	boolean peerInterested = false;
	
	/**
	 *  The info hash in the metainfo file (also part five of the handshake message)
	 */
	byte[] infoHash;
	
	/* Peer information obtained from the tracker request */
	String peerId;
	String peerIp;
	int peerPort;
	
	/* Sockets and streams */
	Socket socket = null;
	DataInputStream socketInput = null;
	DataOutputStream socketOutput = null;
	
	/**
	 *  Handshake message will be created specific to each peer
	 */
	byte[] handshakeMessage;
	
	/**
	 *  Keep track if unchoke and interested have been sent after receipt of bitfield or first have.
	 */
	boolean sentGreetings = false;
	
	/**
	 *  The pieces that the client has.
	 */
	boolean[] peerHaveArray;
	
	/**
	 *  Gives the size of the peerHaveArray, incremented after every have sent by the peer (if the have hasn't been sent before)
	 */
	int peerHaveArraySize = 0;
	
	/**
	 *  The bitfield of the pieces the client has, will be sent to each peer after handshake.
	 */
	Message clientBitfield;
	
	/**
	 *  The bitfield that the peer sent to the client.
	 */
	byte[] peerBitfield = null;
	
	/**
	 *  Whether the client has sent a bitfield to the peer or not.
	 */
	boolean sentBitfield = false;
	
	/**
	 *  The number of pieces that have been downloaded from the peer.
	 */
	int downloadedFromPeer = 0;
	
	/**
	 *  The number of pieces that the peer has, that the client wants. Initialized because its needed to not break out of the while-run loop quickly
	 */
	int wantFromPeer = 0;
	
	/**
	 *  This will be the request queue and it will consist of request messages ready to go out
	 */
	BlockingQueue<Message> requestSendQueue;
	
	/**
	 *  Full of request messages that have been sent, but whose piece have not been sent by the peer yet.
	 */
	BlockingQueue<Message> requestedQueue;
	
	/**
	 *  Queue for sending have messages.
	 */
	volatile BlockingQueue<Message> haveSendQueue;
	
	/**
	 *  The number of requests that have been sent.
	 */
	int requestsSent;
	
	/**
	 *  The max number of requests that can be sent, before waiting for piece messages to arrive;
	 */
	final int MAX_REQUESTS = 10;
	
	/**
	 *  Queue of pieces waiting to be sent.
	 */
	BlockingQueue<Message> pieceSendQueue;
	
	/**
	 *  The pieces that have been sent to a peer
	 */
	int[] peerSendArray;
	
	/**
	 *  If sending a peer the same piece more times than this max, choke the connection
	 */
	final int MAX_SEND_LIMIT = 2;
	
	/**
	 *  When the peer was choked.
	 */
	long lastTimeChoked;
	
	/**
	 *  Amount of time a peer should be choked.
	 */
	final long MAX_CHOKE_INTERVAL = 5000;
	
	/**
	 *  The upload rate to this peer, calculated from amount of pieces they've let us download.
	 */
	int peer_upload_base;
	
	/**
	 *  The smallest number of pieces a peer can download.
	 */
	int PEER_UPLOAD_LOWER_LIMIT = 2;
	
	/**
	 *  The interval in which the max pieces downloaded can be DOWNLOAD_LIMIT.
	 */
	final long MAX_THROTTLE_INTERVAL = 1000;
	
	/**
	 *  The amount of bytes sent since the last interval. Reset every interval of 100 milliseconds.
	 */
	int pieces_sent = 0;
	
	/**
	 *  The amount of bytes received since the last interval. Reset every interval of 100 milliseconds.
	 */
	int pieces_received = 0;
	
	/**
	 *  The last time a reset was performed on the pieces_sent and pieces_received variables.
	 */
	long lastThrottleReset;
	
	/**
	 *  Indicate whether or not the peer is a bad torrent controller. Set to false if client receives a bitfield message from peer.
	 *  Used to help determine if a peer is a seed.
	 */
	boolean badTorrentController = true;
	
	/**
	 *  Indicate if a peer is a seed, to send choke (after file has downloaded)
	 */
	boolean isSeed = false;
	
	/**
	 *  True if peer initiated connection (so it was not in the list sent from the tracker).
	 */
	boolean isRandomPeer = false;
	
	/**
	 *  For sending keep_alives to the peer. The time (in milliseconds) when the last message was sent.
	 */
	volatile long clientLastMessage;
	
	/**
	 *  For keeping a connection alive with a peer. The time (in milliseconds) when the last message was received.
	 */
	volatile long peerLastMessage;
	
	/**
	 *  The interval (in milliseconds) in which a message needs to be communicated to a peer to keep the connection alive.
	 */
	volatile long MAX_KEEPALIVE_INTERVAL = 110000;
	
	/**
	 *  How long the client should try to connect to a peer before giving up. (in milliseconds)
	 */
	final int CONNECTION_TIMEOUT = 3000;
	
	/**
	 *  An attempt was made to connect to this peer.
	 */
	boolean isAttempted = false;
	
	/**
	 *  Number of times the client has tried to connect to this peer.
	 */
	int connectionAttempts = 0;
	
	/**
	 *  If the peer is currently connected to, and exchanging messages with, the client.
	 */
	boolean isActive = false;
	
	/**
	 *  If it's ok to close the connection if the peer dosn't have any pieces the client wants, and if neither are interested in eachother
	 */
	boolean canQuit = false;
	
	/**
	 *  True while there are no problems in working with the peer.
	 */
	boolean RUN = true;
	
	/**
	 * Here lies the constructer for the Peer class. Used for the peers that came back in the tracker response.
	 * 
	 * @param peer_id
	 * @param peer_ip
	 * @param peer_port
	 * @param client
	 * @throws IOException
	 */
	public Peer(String peer_id, String peer_ip, int peer_port, RUBTClient client) {
		
		//Initialize the new Peer object
		this.client = client;
		this.peerId = peer_id;
		this.peerIp = peer_ip;
		this.peerPort = peer_port;
		this.infoHash = client.torrentInfo.info_hash.array();
		this.handshakeMessage = createHandshake();
		this.peerHaveArray = new boolean[client.numOfPieces];
		this.peerSendArray = new int[client.numOfPieces];
		this.requestSendQueue = new LinkedBlockingQueue<Message>(client.numOfPieces);
		this.requestedQueue = new LinkedBlockingQueue<Message>(client.numOfPieces);
		this.pieceSendQueue = new LinkedBlockingQueue<Message>(client.numOfPieces);
		this.haveSendQueue = new LinkedBlockingQueue<Message>(client.numOfPieces);
		
		// Set up the bitfield
		this.clientBitfield = Message.createBitfield(client);
		
		//Set up the text file for the logger.
		/*if (logger.getHandlers().length == 0) { // Or would this not matter because logger has "static" and "final" modifier...
			try {
				logger.setUseParentHandlers(false); // Don't print logs to the console
				FileHandler fh = new FileHandler("PeerLogger.txt");
				logger.addHandler(fh);
				SimpleFormatter sf = new SimpleFormatter();
				fh.setFormatter(sf);
			} catch (IOException e) {
				System.out.println("The logger text file could not be set up.");
				e.printStackTrace();
			}
		}*/
	}
	
	
	/**
	 * This is the thread for a peer object. It connects to a peer and starts downloading from them.
	 */
	public void run() {
        	
		//while (!Thread.interrupted()){
        // If starting a new connection or restarting a connection...
       	if (this.socketInput == null) this.connect();
       	
       	
       	// Send bitfield
        if (!this.sentBitfield && this.clientBitfield != null) {
        	this.sendMessage(this.clientBitfield);
        	this.sentBitfield = true;
        }
        
        this.clientLastMessage = System.currentTimeMillis();
        this.peerLastMessage = System.currentTimeMillis();
        this.lastThrottleReset = System.currentTimeMillis();
        this.lastTimeChoked = System.currentTimeMillis();
        
        Timer timer = new Timer();
        TimerTask timerTask = new TimerTask() {
        	
        	// Again, leaving the Peer.this in for clarification
        	public void run() {
        		
    			// Send keep alives to peers
    			if (System.currentTimeMillis() - Peer.this.clientLastMessage > Peer.this.MAX_KEEPALIVE_INTERVAL)
    				if (Peer.this.amInterested) {
    					Peer.this.sendMessage(Message.createKeepAlive());
    					Peer.this.clientLastMessage = System.currentTimeMillis();
    					Peer.this.peerLastMessage = System.currentTimeMillis();
    				}
    			
    			// Check if the peer wants to keep the connection open
    			if (System.currentTimeMillis() - Peer.this.peerLastMessage > Peer.this.MAX_KEEPALIVE_INTERVAL + 25000) {
    				Peer.this.shutdown();
    			}
    			
    			Peer.this.canQuit = true;
    		}
       	};
        timer.schedule(timerTask, this.MAX_KEEPALIVE_INTERVAL, this.MAX_KEEPALIVE_INTERVAL);
        
        while (this.RUN) {
        	
        	// Reset throttle
        	if (System.currentTimeMillis() - this.lastThrottleReset > this.MAX_THROTTLE_INTERVAL) {
        		this.pieces_received = 0;
        		this.pieces_sent = 0;
        		this.lastThrottleReset = System.currentTimeMillis();
        	}
        	
        	//System.out.println("1");
        	// Set the upload rate
        	if (this.downloadedFromPeer != 0)
        		this.peer_upload_base = (int)(((double)this.downloadedFromPeer / this.wantFromPeer)*this.client.UPLOAD_LIMIT) + this.PEER_UPLOAD_LOWER_LIMIT;
        	else
        		this.peer_upload_base = this.PEER_UPLOAD_LOWER_LIMIT;
        	
        	//System.out.println("2");
        	// Read the incoming message if within throttle
        	if (this.pieces_received < this.client.DOWNLOAD_LIMIT) {
				try {
					Message.dealWithMessage(this);
				} catch (IOException e) {
					logger.info("IO exception (in peer class, dealWithMessage()) from Peer "
							+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
							+ " ].");
					this.shutdown();
				}
        	}
        	
        	//System.out.println("3");
        	// Reset throttle
        	if (System.currentTimeMillis() - this.lastThrottleReset > this.MAX_THROTTLE_INTERVAL) {
        		this.pieces_received = 0;
        		this.pieces_sent = 0;
        		this.lastThrottleReset = System.currentTimeMillis();
        	}
        	
        	//System.out.println("4");
        	// If I'm choking, clear the piece send queue
        	if (this.amChoking) {
        		this.pieceSendQueue.clear();
        		if (System.currentTimeMillis() - this.lastTimeChoked > this.MAX_CHOKE_INTERVAL) {
        			this.sendMessage(Message.createUnchoke());
        		} else {
        			continue;
        		}
        	}
        	
        	//System.out.println("5");
        	// While peer is choking, wait until it is done choking
			if (this.peerChoking) {
				continue;
			}
			
			//System.out.println("6");
			// Send requests to the peer, MAX_REQUESTS out at one time
			while (!this.amChoking && !this.peerChoking && this.requestsSent < this.MAX_REQUESTS && !this.requestSendQueue.isEmpty()) {
				Message message = this.requestSendQueue.poll();
				if (!this.requestedQueue.contains(message)) this.requestedQueue.add(message);
				this.sendMessage(message);
				this.requestsSent++;
			}
			
			//System.out.println("7");
			// Send pieces from requests
			while (!this.amChoking && !this.peerChoking && this.pieces_sent < this.peer_upload_base && !this.pieceSendQueue.isEmpty()) {
				try {
					this.sendMessage(this.pieceSendQueue.take());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			//System.out.println("8");
			// Send have messages
			while (!this.amChoking && !this.peerChoking && !this.haveSendQueue.isEmpty()) {
				try {
					this.sendMessage(this.haveSendQueue.take());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			//System.out.println("9");
			// If have all pieces, send uninterested, and if neither client not peer are interested in each other then close the connection
			if (this.canQuit && this.requestSendQueue.isEmpty() && this.requestedQueue.isEmpty()) {
				logger.info("All pieces downloaded from Peer " 
						+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
						+ " ].");
				if (!this.client.amSeeding) this.sendMessage(Message.createNotInterested());
				if (!this.peerInterested) this.shutdown();
			}
				
        }
        
        timer.cancel();
        
        logger.info("End of thread with Peer " 
				+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
				+ " ].");
        this.interrupt();
	}
	
	/**
	 * This method sets up a connection with the peer. And sends a bitfield if there is one.
	 */
	public void connect() {
		try {
			this.isAttempted = true;
			if (!this.isRandomPeer) {
				this.socket = new Socket();
				try {
					this.socket.connect(new InetSocketAddress(peerIp, peerPort), CONNECTION_TIMEOUT);
				} catch (SocketTimeoutException e) {
					logger.info("Connection timed out with Peer "
							+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
							+ " ].");
					this.RUN = false;
					return;
				}
			} else {
				logger.info("Incoming connection from IP : " + this.socket.getInetAddress().getHostAddress());
			}
			this.socketInput = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			this.socketOutput = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
			logger.info("Connection established with Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ].");
	        if (this.isRandomPeer) {
	        	this.receiveHandshake();
	        	this.sendHandshake();
	        } else {
	        	this.sendHandshake();
	        	this.receiveHandshake();
	        }
	        this.isActive = true;
	        this.client.neighboring_peers.add(this);
	        this.client.numOfActivePeers++;
		} catch (ConnectException e) {
			logger.info("Unsuccessful connection with Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ]. UnknownHostException.");
			System.out.println(e + " Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ].");
			this.shutdown();
		} catch (UnknownHostException e) {
			logger.info("Unsuccessful connection with Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ]. UnknownHostException.");
			e.printStackTrace();
			this.shutdown();
		} catch (IOException e) {
			logger.info("Unsuccessful connection with Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ]. IOException.");
			e.printStackTrace();
			this.shutdown();
		}
	}
	
	/**
	 * This method closes a connection with a peer.
	 */
	public void disconnect() {
		System.out.println("Disconnecting from Peer "
				+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
				+ " ].");
		try {
			if (this.socket != null) this.socket.close();
			logger.info("Disconnected from Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ].");
		} catch (IOException e) {
			logger.info("Unable to disconnect from Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ]. May have already been disconnected.");
			System.err.println(e);
		}
	}
	
	
	/**
	 *  Closes the connection with a peer and ends the thread.
	 */
	public void shutdown() {
		// This check was put here because threads are capricious
		if (this.isAttempted) {
			
			this.connectionAttempts++;
			// In case MAX_CONNECTION_ATTEMPTS was accidentally set to unreasonable value, >= is needed
			if (this.connectionAttempts >= this.client.MAX_CONNECTION_ATTEMPTS) {
				this.client.bad_peers.add(this.peerId);
				logger.info("Peer ID : [ " + this.peerId + " ] has been added to the list of bad peers.");
			}
			
			this.isAttempted = false;
			this.client.neighboring_peers.remove(this.peerIp);
			this.client.numOfActivePeers--;
		}
		if (!this.socket.isClosed())
			this.disconnect();
		this.RUN = false;
	}
	
	
	/**
	 * This method will shake hands with a peer.
	 */
	public byte[] createHandshake() {
		byte[] handshakeMessage = new byte[68];
		try {
			ByteBuffer byteBuffer = ByteBuffer.wrap(handshakeMessage);
			byteBuffer.put((byte) 19);
			byteBuffer.put("BitTorrent protocol".getBytes());
			byteBuffer.put(new byte[8]);
			byteBuffer.put(this.infoHash);
			byteBuffer.put(this.client.clientId.getBytes());
			return handshakeMessage;
		} catch (BufferOverflowException e) {
			return null;
		}
	}
	
	
	/**
	 *  Send your handshake to the peer.
	 */
	public void sendHandshake() {
		//Send your handshake
		try {
			this.socketOutput.write(this.handshakeMessage);
			//System.out.println("Sent: " + Arrays.toString(this.handshakeMessage));
			this.socketOutput.flush();
	        logger.info("Handshake message was sent to Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
	        		+ " ].");
		} catch (IOException e) {
	        logger.info("A problem occurred while trying to send the handshake message to Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
	        		+ " ].");
			System.err.println(e);
			this.shutdown();
		}
	}
	
	
	/**
	 *  Read the handshake sent from a peer.
	 */
	public void receiveHandshake() {
		
        // Receive the peer handshake and verify it
        byte[] handshakeBytes = new byte[68];
        try {
			this.socketInput.read(handshakeBytes);
			//System.out.println("Recv: " + Arrays.toString(handshakeBytes));

			if (Message.verifyHandshake(this.handshakeMessage, handshakeBytes))
				logger.info("The handshake response from Peer "
						+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
						+ " ] was verified.");
			else {
				logger.info("The handshake response from Peer "
						+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
						+ " ] was incorrect. Closing connection...");
				this.shutdown();
			}
			if (this.peerId == null) this.peerId = new String(Arrays.copyOfRange(handshakeBytes, 48, 68));
		} catch (IOException e) {
			System.err.println(e);
			logger.info("Connection dropped. Incorrect handshake sent by Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ].");
			this.shutdown();
		}
	}
	
	
	/**
	 *  This method sends a message to a peer.
	 * 
	 * @param message -> the message that will be sent
	 */
	public synchronized void sendMessage(Message message) {
		if (this.socketOutput == null || message == null) { // Sanity check
			return;
		}
		if (this.socket.isClosed()) {
			/*logger.info("Could not send {" + message_types[message.message_id + 1] + "} message to Peer "
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ]. Socket is closed.");*/
			return;
		}
		//logger.info("Sending {" + message_types[message.message_id + 1] + "} message to Peer " + ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp)) + " ].");

		//System.out.println("Sending {" + message_types[message.message_id + 1] + "} message to Peer "
			//	+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
			//	+ " ].");

		System.out.println("Sending {" + message_types[message.message_id + 1] + "} message to Peer IP : [ " + this.peerIp);


		try {
			this.socketOutput.writeInt(message.length_prefix);
			if (message.message_id != -1) {
				this.socketOutput.writeByte(message.message_id);
				if (message.message_id == 0) this.amChoking = true;
				if (message.message_id == 1) this.amChoking = false;
				if (message.message_id == 2) this.amInterested = true;
				if (message.message_id == 3) this.amInterested = false;
			}
			if (message.intPayload != null) {
				int[] tempArray = message.intPayload;
				for (int i = 0; i < tempArray.length; i++)
					this.socketOutput.writeInt(tempArray[i]);
			}
			if (message.bytePayload != null) {
				byte[] tempArray = message.bytePayload;
				for (int i = 0; i < tempArray.length; i++)
					this.socketOutput.writeByte(tempArray[i]);
			}
			this.socketOutput.flush();
			this.client.bytesUploaded = this.client.bytesUploaded + message.length_prefix;
			if (message.message_id == 7) {
				this.pieces_sent++;
				this.peerSendArray[message.intPayload[0]]++;
				if (this.peerSendArray[message.intPayload[0]] > this.MAX_SEND_LIMIT) {
					this.peerSendArray[message.intPayload[0]] = 0;
					this.sendMessage(Message.createChoke());
					this.lastTimeChoked = System.currentTimeMillis();
				}
			}
		this.clientLastMessage = System.currentTimeMillis();
		this.peerLastMessage = System.currentTimeMillis();
		} catch (IOException e) {
			System.out.println(e.toString());
			logger.info("A problem occurrred while trying to send a {" + message_types[message.message_id + 1] + "} message to Peer " 
					+ "ID : [ " + this.peerId + "] with IP : [ " + this.peerIp
					+ " ].");
			this.shutdown();
		}
	}
}
