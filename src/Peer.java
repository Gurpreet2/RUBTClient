/*
 * @author Gurpreet Pannu, Michael Norris, Priyam Patel
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
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
	 *  An array made up of the have messages this peer has sent to the client
	 */
	boolean[] peerHaveArray;
	
	/**
	 *  Gives the size of the peerHaveArray, incremented after every have sent by the peer (if the have hasn't been sent before)
	 */
	int peerHaveArraySize = 0;
	
	/**
	 *  This will be the request queue and it will consist of request messages ready to go out
	 */
	BlockingQueue<Message> sendQueue;
	
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
	 *  The bitfield of the pieces the client has, will be sent to each peer after handshake.
	 */
	Message clientBitfield;
	
	boolean sentBitfield = false;
	
	/**
	 *  The bitfield that the peer sent to the client, but in a boolean array.
	 */
	boolean[] peerBytefield = null;
	
	/**
	 *  The number of pieces the peer actually has.
	 */
	int sizeOfBytefield;
	
	/**
	 *  The number of pieces that have been downloaded from the peer.
	 */
	int downloadedFromPeer;
	
	/**
	 *  True if handshake was sent.
	 */
	boolean sentHandshake = false;
	
	/**
	 *  Keep track if unchoke and interested have been sent after handshake and receipt of bitfield or first have
	 */
	boolean sentGreetings = false;
	
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
	 *  The amount of bytes sent since the last interval. Reset every interval of 100 milliseconds.
	 */
	int bytes_sent = 0;
	
	/**
	 *  The amount of bytes received since the last interval. Reset every interval of 100 milliseconds.
	 */
	int bytes_received = 0;
	
	/**
	 *  The interval in which the max bytes sent can be UPLOAD_LIMIT and the max bytes downloaded can be DOWNLOAD_LIMIT.
	 */
	final long MAX_THROTTLE_INTERVAL = 100;
	
	/**
	 *  If the peer is currently connected to, and exchanging messages with, the client.
	 */
	boolean isActive = false;
	
	/**
	 *  Number of times the client has tried to connect to this peer.
	 */
	int connectionAttempts = 0;
	
	/**
	 *  An attempt was made to connect to this peer.
	 */
	boolean isAttempted = false;
	
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
        	
		while(!Thread.interrupted()){
	        // If starting a new connection or restarting a connection...
	       	if (this.socket == null) this.connect();
	       	//if (this.socket != null) logger.info("Started exchanging messages with Peer ID : [ " + this.peerId + " ].");

	       	
	       	// Send bitfield
	        if (!this.sentBitfield && this.clientBitfield != null) {
	        	this.sendMessage(this.clientBitfield);
	        }
	        
	        int count = 0;
	        while (this.RUN && this.client.RUN) {
	        	
	        	if (this.socketInput == null) this.connect();
	        	
				try {
					Message.dealWithMessage(this);
				} catch (IOException e) {
					logger.info("IO exception (in peer class) from Peer : " + this.peerId);
					MyTools.saveDownloadedPieces(this.client);
					this.shutdown();
				}
				
				if (this.peerChoking) {
					continue;
				}
				
				if (count < this.client.numOfPieces && this.client.numOfHavePieces < this.client.numOfPieces && this.peerBytefield != null) {
					while (!this.peerBytefield[count] || this.client.havePieces[count]) {
						count++;
						if (count == this.client.numOfPieces) break;
					}
					if (count == this.client.numOfPieces) {
						this.sendMessage(Message.createNotInterested());
						continue;
					}
					this.sendMessage(Message.createRequest(count, 0, this.client.torrentInfo.piece_length));
					count++;
				} else if (count == this.client.numOfPieces && this.downloadedFromPeer == this.sizeOfBytefield) {
					logger.info("All pieces downloaded from peer: " + this.peerId);
					this.amInterested = false;
					System.out.println("All pieces downloaded from peer: " + this.peerId);
					MyTools.saveDownloadedPieces(client);
					if (!this.peerInterested) this.shutdown();
				}
	        }
	        logger.info("End of thread with Peer " 
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ].");
		}
	}
	
	
	/**
	 * This method sets up a connection with the peer. And sends a bitfield if there is one.
	 */
	public void connect() {
		try {
			this.isAttempted = true;
			if (!this.isRandomPeer)
				this.socket = new Socket(peerIp, peerPort);
			else
				logger.info("Incoming connection from IP : " + this.socket.getInetAddress().getHostAddress());
			this.socketInput = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			this.socketOutput = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
			logger.info("Connection established with Peer " 
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ].");
	        if (this.isRandomPeer) {
	        	this.receiveHandshake();
	        	this.sendHandshake();
	        } else {
	        	this.sendHandshake();
	        	this.receiveHandshake();
	        }
	        this.isActive = true;
	        this.client.cur_peer_interactions.add(this.peerIp);
	        this.client.numOfActivePeers++;
		} catch (ConnectException e) {
			logger.info("Unsuccessful connection with Peer " 
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ]. UnknownHostException.");
			System.out.println(e + " Peer: " + ((this.peerId != null) ? this.peerId : this.peerIp));
			this.shutdown();
		} catch (UnknownHostException e) {
			logger.info("Unsuccessful connection with Peer " 
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ]. UnknownHostException.");
			e.printStackTrace();
			this.shutdown();
		} catch (IOException e) {
			logger.info("Unsuccessful connection with Peer " 
				+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ]. IOException.");
			e.printStackTrace();
			this.shutdown();
		}
	}
	
	/**
	 * This method closes a connection with a peer.
	 */
	public void disconnect() {
		System.out.println("Disconnecting from Peer ID: " + this.peerId);
		try {
			if (this.socket != null) this.socket.close();
			logger.info("Disconnected from Peer "
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ].");
		} catch (IOException e) {
			logger.info("Unable to disconnect from Peer "
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
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
			this.client.cur_peer_interactions.remove(this.peerIp);
			this.client.numOfActivePeers--;
		}
		this.disconnect();
		this.interrupt();
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
			this.sentHandshake = true;
	        logger.info("Handshake message was sent to Peer ID : [ " + this.peerId + " ].");
		} catch (IOException e) {
	        logger.info("A problem occurred while trying to send the handshake message to Peer ID : [ " + this.peerId + " ].");
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
						+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
						+ " ] was verified.");
			else {
				logger.info("The handshake response from Peer "
						+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
						+ " ] was incorrect. Closing connection...");
				this.shutdown();
			}
			if (this.peerId == null) this.peerId = new String(Arrays.copyOfRange(handshakeBytes, 48, 68));
		} catch (IOException e) {
			System.err.println(e);
			logger.info("Connection dropped. Incorrect handshake sent by Peer "
					+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
					+ " ].");
			this.shutdown();
		}
	}
	
	
	/**
	 *  This method sends a message to a peer.
	 * 
	 * @param message -> the message that will be sent
	 */
	public void sendMessage(Message message) {
		this.clientLastMessage = System.currentTimeMillis();
		if (this.socket == null) {
			logger.info("Could not send {" + message_types[message.message_id + 1]
					+ "} message to Peer ID : [ " + this.peerId + " ]. Socket is closed.");
			shutdown();
		}
		if (this.socket.isClosed()) {
			logger.info("Could not send {" + message_types[message.message_id + 1]
					+ "} message to Peer ID : [ " + this.peerId + " ]. Socket is closed.");
			shutdown();
		}
		//logger.info("Sending {" + message_types[message.message_id + 1] + "} message to Peer " + ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp)) + " ].");
		System.out.println("Sending {" + message_types[message.message_id + 1] + "} message to Peer " 
				+ ((this.peerId != null) ? ("ID : [ " + this.peerId) : ("IP : [ " + this.peerIp))
				+ " ].");
		try {
			this.socketOutput.writeInt(message.length_prefix);
			if (message.message_id != (byte) -1) {
				this.socketOutput.writeByte(message.message_id);
				if (message.message_id == (byte) 1) this.amChoking = false;
				if (message.message_id == (byte) 2) this.amInterested = true;
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
		} catch (IOException e) {
			System.out.println(e.toString());
			logger.info("A problem occurrred while trying to send a {" + message_types[message.message_id + 1] + "} message to peer: [ " 
					+ this.peerId + " ].");
			this.shutdown();
		}
	}
}
