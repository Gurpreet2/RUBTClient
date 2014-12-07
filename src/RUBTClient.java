/*
 *  @author Gurpreet Pannu, Priyam Patel, Michael Norris
 */



import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import javax.swing.JFrame;

import Tools.TorrentInfo;

public class RUBTClient extends JFrame implements Runnable{
	
	/**
	 *  Needed this.
	 */
	private static final long serialVersionUID = 1L;
	
	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(RUBTClient.class.getName());
	
	/**
	 *  The name of the torrent metainfo file.
	 */
	static String metainfoFile;
	
	/**
	 *  The name of the torrent download file.
	 */
	static String downloadFile;
	
	/**
	 *  A peer ip. If specified, client will connect to it and start downloading from it.
	 */
	String onlyPeer = null;
	
	/**
	 *  This will be the peer id associated with the client
	 */
	String clientId;
	
	/**
	 *  This will be the amount of bytes left to download from peers, initially will be set to size of full file
	 */
	volatile int bytesLeft;
	
	/**
	 *  How many bytes have been uploaded to peers. (Initialization only for fresh downloads, corrected in main if continuing download)
	 */
	volatile int bytesUploaded = 0;

	/**
	 *  How many bytes have been downloaded so far. (Initialization only for fresh downloads, corrected in main if continuing download)
	 */
	volatile int bytesDownloaded = 0;
	
	/**
	 *  The metainfo file of the torrent will be stored in here
	 */
	TorrentInfo torrentInfo;
	
	/**
	 *  Response from the tracker after sending it a "started" announcement will be stored in response_1
	 */
	Response response1;

	/**
	 *  Responses from the tracker after sending it periodic announcements will be stored in response_2
	 */
	Response response2;
	
	/**
	 *  This arraylist stores the peers in a peer objects arraylist
	 */
	volatile ArrayList<Peer> peers;
	
	/**
	 *  Number of blocks there are to download
	 */
	volatile int numOfPieces;
	
	/**
	 *  This will contain the raw bytes being downloaded
	 */
	volatile byte[] rawFileBytes;
	
	/**
	 *  A boolean array indicating whether or not a piece has been downloaded. Will help for sending bitfield.
	 */
	volatile boolean[] havePieces;
	
	/**
	 *  The number of pieces the client has.
	 */
	volatile int numOfHavePieces = 0;
	
	/**
	 *  An array that will be used to send have messages to peers 
	 *  (set to true when piece finishes downloading, reset to false after sending have message to all peers)
	 */
	volatile LinkedBlockingQueue<Message> haveSendQueue = new LinkedBlockingQueue<Message>(20);
	
	/**
	 *  This will be used to send requests to peers, and to limit the amount of requests to send.
	 */
	volatile LinkedBlockingQueue<Message> requestSendQueue = new LinkedBlockingQueue<Message>(20);
	
	/**
	 *  Interval for sending announcements to tracker.
	 */
	static int announceTimerInterval = 180;
	
	/**
	 *  The number of active peers. This value is changed after handshakes are sent and received. Also after peers are disconnected
	 *  via the shutdown method.
	 */
	volatile int numOfActivePeers = 0;
	
	/**
	 *  Number of bytes that can be uploaded in interval MAX_THROTTLE_INTERVAL by each peer.
	 *  (MAX_THROTTLE_INTERVAL is in Peer.java...)
	 */
	volatile int UPLOAD_LIMIT;
	
	/**
	 *  Maximum number of bytes that can be uploaded in interval MAX_THROTTLE_INTERVAL.
	 */
	final int MAX_UPLOAD_LIMIT = 10000;
	
	/**
	 *  Number of bytes that can be downloaded in interval MAX_THROTTLE_INTERVAL by each peer.
	 */
	volatile int DOWNLOAD_LIMIT;
	
	/**
	 *  Maximum number of bytes that can be downloaded in interval MAX_THROTTLE_INTERVAL.
	 */
	final int MAX_DOWNLOAD_LIMIT = 10000;
	
	/**
	 *  Amount of times a client should try to connect to a peer before giving up on the peer.
	 */
	final int MAX_CONNECTION_ATTEMPTS = 3;
	
	/**
	 *  List of IPs of peers that the client shouldn't connect to, because previous connection attempts have ended in failure.
	 */
	ArrayList<String> bad_peers = new ArrayList<String>(10);
	
	/**
	 *  Maximum number of connections allowed.
	 */
	final int MAX_CONNECTIONS = 10;
	
	/**
	 *  List of Peers that the client is currently interacting with;
	 */
	ArrayList<String> cur_peer_interactions = new ArrayList<String>(10);

	/**
	 *  Value that is true while file is downloading, false when file finishes downloading.
	 */
	volatile boolean RUN = true;
	
	public RUBTClient() {
		
		// Generate a peer ID for the client
		String str = UUID.randomUUID().toString();
		this.clientId = "-RUBT11-" + str.substring(0,8) + str.substring(9,13);// + str.substring(14,16);// + str.substring(19,23);
	}
	
	public static void main(String[] args) {
		
		RUBTClient client = new RUBTClient();
		
		// Make sure that the number of command line arguments is 2 or 3
		if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: java RUBTClient <torrent file> <download file>\n");
            System.out.println("or\n");
            System.out.println("Usage: java RUBTClient <torrent file> <download file> <peer ip>\n");
            return;
        }
		
		// Create a text file for the logger
		/*try {
			logger.setUseParentHandlers(false); // Don't print logs to the console. (Less clutter)
			FileHandler filehandler = new FileHandler("logger.txt");
			logger.addHandler(filehandler);
			SimpleFormatter simpleformatter = new SimpleFormatter();
			filehandler.setFormatter(simpleformatter);
		} catch (IOException e) {
			System.err.println("The logger text save file could not be set up.");
			e.printStackTrace();
		}*/
		
		// Hold the cammand line arguments in some variables so they can be called from methods outside main
		RUBTClient.metainfoFile = args[0];
		RUBTClient.downloadFile = args[1];
		if (args.length == 3) client.onlyPeer = args[2];
		
		// Convert the torrent metainfo file into a TorrentInfo file, and use it to initialize some variables for client
        client.torrentInfo = MyTools.getTorrentInfo(RUBTClient.metainfoFile);
        client.numOfPieces = client.torrentInfo.piece_hashes.length;
        // If the client was stopped earlier, and the download file exists, fill rawFileBytes with the bytes from the file
        if (!MyTools.setDownloadedBytes(client)) {
        	logger.info("Download file found, resuming download.");
        } else {
        	// Else, initialize variables needed for a fresh download
        	logger.info("Starting new download.");
	        client.bytesLeft = client.torrentInfo.file_length;
	        client.havePieces = new boolean[client.numOfPieces];
	        client.rawFileBytes = new byte[client.torrentInfo.file_length];
        }
        
        // Connect to the Tracker and send a started announcement
        new Request(client, "started");
        logger.info("Sent started announcement to tracker.");
        
        // Check if something went wrong with the tracker response and print the failure reason.
        if (client.response1.failure_reason != null) {
        	System.out.println("Torrent failed to download, failure reason:\n   " + client.response1.failure_reason);
        	logger.info(client.response1.failure_reason);
        	return;
        }
        
        /*// Input Reader
        InputReader ir = client.new InputReader();
        ir.start();*/
        
        // Put peer_map into a peer array so it can be more easily used and read, also start the threads for each peer
        if (args.length == 2) {
	        ArrayList<Map<ByteBuffer, Object>> peer_map = client.response1.get_peer_map();
	        client.peers = MyTools.createPeerList(client, peer_map);
	        // Start connecting to and messaging peers
	        for (Peer peer : client.peers) {
				if (client.numOfActivePeers < client.MAX_CONNECTIONS) {
					if(peer.peerIp.startsWith("128.6.171.")){ //TODO: Allow all peers
						client.cur_peer_interactions.add(peer.peerIp);
		        		System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
		    			peer.start();
					}
	    		} else
	    			logger.info("Unable to connect with Peer ID : [ " + peer.peerId + " ]. Maximum number of connections reached.");
	        }
        } else {
        	int port, count = 0;
        	do {
        		port = MyTools.findPort(client.onlyPeer);
        		count++;
        	} while (port == 0 && count < 3);
        	if (count == 3) {
        		logger.info("No open port found for single Peer: (" + client.onlyPeer + "). Exiting program...");
        		System.err.println("Could not find an open port to connect to the peer in the range 6881 - 6890. Exiting...");
        		return;
        	}
        	System.out.println("Added peer");
        	client.peers.add(new Peer(null, client.onlyPeer, port, client));
        }
        //client.peers.get(0).start();
        
        // Thread start for client
        new Thread(client).start();
        startGui(client);
        
		if (client.onlyPeer == null) {
			PeerListener peerListener = client.new PeerListener();
			peerListener.runPeerListener();
		}
	}


	/**
	 *  This is the thread for the client. It sends periodic tracker announcements, waits for input
	 *  from the user, sends have messages, and requests, and runs until the file is downloaded.
	 */
	public void run() {
		
        logger.info("Started thread for client.");
		// Setup a timer to send periodic announcements to the tracker.
		Timer timer = new Timer();
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				
				/* I left "RUBTClient.this" in front of the relevant variables for clarity. */
				// Send the regular announcement
				new Request(RUBTClient.this, "");
				logger.info("Regular announcement sent to tracker.");
				// Update the tracker announce interval if need be
				if (RUBTClient.this.response2 != null) //sanity check
					RUBTClient.announceTimerInterval = 
						(RUBTClient.this.response2.interval > 180) ? 180 : RUBTClient.this.response2.interval;
				
				if (RUBTClient.this.onlyPeer == null) {
					// Parse the tracker response and add in new peers
					ArrayList<Map<ByteBuffer, Object>> peer_map = RUBTClient.this.response2.get_peer_map();
			        ArrayList<Peer> peers = MyTools.createPeerList(RUBTClient.this, peer_map);
			        for (Peer peer : peers) {
			        	if (RUBTClient.this.numOfActivePeers < RUBTClient.this.MAX_CONNECTIONS 
			        			&& !RUBTClient.this.cur_peer_interactions.contains(peer.peerIp) 
			        			&& !RUBTClient.this.bad_peers.contains(peer.peerIp)) {
			        		RUBTClient.this.cur_peer_interactions.add(peer.peerIp);
				        	System.out.println("Added peer");

			        		RUBTClient.this.peers.add(peer);
			        		System.out.println("Starting thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
			    			peer.start();
			        	}
			        }
		        
			        // Look at the old peers and see if any of them deserve a chance to be connected to, again
			        for (Peer peer : RUBTClient.this.peers) {
			        	if (peer.connectionAttempts < RUBTClient.this.MAX_CONNECTION_ATTEMPTS 
			        			&& (peer.peerIp != null ? !RUBTClient.this.cur_peer_interactions.contains(peer.peerIp) : true) // Adjusted for incoming peer connections
			        			// In case I add something later on that puts peers in bad_peers even when connectionAttempts < MAX_CONNECTION_ATTEMPTS
			        			&& !RUBTClient.this.bad_peers.contains(peer.peerIp)) {
			        		RUBTClient.this.cur_peer_interactions.add(peer.peerIp);
			        		System.out.println("Starting NEW thread for Peer ID : [ " + peer.peerId + " ] and IP : [ " + peer.peerIp +" ].");
			    			peer.start();
			        	}
			        }
				}
			}
		};
		timer.schedule(timerTask, RUBTClient.announceTimerInterval*1000, RUBTClient.announceTimerInterval*1000);
		
		int downloaded = this.bytesDownloaded;
		System.out.println("Download is " + (this.bytesDownloaded*100/this.rawFileBytes.length) + "% complete.");
		while(this.RUN) {
		
			// Send keep alives to peers
			for (Peer peer : this.peers) {
				if (peer.clientLastMessage + peer.MAX_KEEPALIVE_INTERVAL > System.currentTimeMillis())
					if (peer.amInterested) {
						//peer.sendMessage(Message.createKeepAlive());
						peer.clientLastMessage = System.currentTimeMillis();
						peer.peerLastMessage = System.currentTimeMillis();
					}
			
				// Check if the peer wants to keep the connection open
				if (peer.peerLastMessage + peer.MAX_KEEPALIVE_INTERVAL + 25000> System.currentTimeMillis()) {
					//peer.shutdown();
				}
				if(peer.isActive == false){
		        	//System.out.println("Removed peer");
		        	//peer.interrupt();
					//this.peers.remove(peer);
				}
			}
			
			// Set the upload and download limits for each peer
			if (this.numOfActivePeers < 1) {
				this.UPLOAD_LIMIT = this.MAX_UPLOAD_LIMIT;
				this.DOWNLOAD_LIMIT = this.MAX_DOWNLOAD_LIMIT;
			} else {
				this.UPLOAD_LIMIT = this.MAX_UPLOAD_LIMIT / this.numOfActivePeers;
				this.DOWNLOAD_LIMIT = this.MAX_DOWNLOAD_LIMIT / this.numOfActivePeers;
			}
			
			// Prints how far along the download is, whenever the download progresses.
			if (downloaded != this.bytesDownloaded) {
				System.out.println("Download is " + (this.bytesDownloaded*100/this.rawFileBytes.length) + "% complete.");
				downloaded = this.bytesDownloaded;
			}
			
			// This sends have messages when required
			if (!this.haveSendQueue.isEmpty()) {
				try {
					Message message = this.haveSendQueue.take();
					for (Peer peer : peers) {
						if (peer.socket == null || peer.socketOutput == null) continue;
						if (peer.socket.isClosed()) continue;
						peer.sendMessage(message);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (this.bytesLeft == 0) this.RUN = false;
		}
		
		timer.cancel();
		
		if (this.bytesLeft == 0) {// Just making sure that there are no more bytes left to download, in case the above loop had some problem
			new Request(this, "completed");
			logger.info("Sent completed announcement to tracker.");
		}
        
        // After the threads have been shutdown, save the downloaded rawfilebytes into a file.
        MyTools.saveDownloadedPieces(this);
	}
	
	
	/**
	 * Creates a Gui object and opens up the interface.
	 */
	private static void startGui(RUBTClient rc){
		Gui gui;
		gui = new Gui(rc);
		gui.setSize(450,450);
		gui.setResizable(false);
		gui.setVisible(true);
		gui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
	}
	
	
	/** Changes made by: Priyam Patel 
	 *  Added method is called from GUI to update progress bar 
	 * @returns int: percent value left to download 
	 */
	public int getProgressBarPercent() {
		double fraction = ((double)this.bytesDownloaded/(double)this.torrentInfo.file_length);
		return (int)fraction*100;
	}
	
	
	/**
	 * This class listens for incoming connections from peers and allocates a socket for a connection.
	 *
	 */
	class PeerListener extends Thread {
		
		public PeerListener() {
		}
		
		public void runPeerListener() {
			
			ServerSocket serverSocket;
			Socket peerSocket;
			
			try {
				serverSocket = new ServerSocket(Request.port);
				logger.info("PeerListener is waiting for connections.");
				
				while (true) {
					peerSocket = serverSocket.accept();
					newPeer(peerSocket);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public void newPeer(Socket socket) {
			
			Peer peer = new Peer(null, socket.getInetAddress().getHostAddress(), socket.getPort(), RUBTClient.this);
			//RUBTClient.this.peers.add(peer);
			//if (RUBTClient.this.numOfActivePeers < RUBTClient.this.MAX_CONNECTIONS)
				//peer.start();
		}
	}
	
	
	/**
	 * This class reads input from the user
	 * TODO fix this
	 * 
	 */
	class InputReader extends Thread{
		
		public InputReader () {
			
		}
		
		public void run() {
			BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));
			String str;
			
			try {
				str = inputReader.readLine().toLowerCase();
				while (true) {
					if (str.equals("q") || str.equals("quit")) break;
					if (str.equals("stop")) pause();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			for (Peer peer : RUBTClient.this.peers) {
				if (peer.socket != null) {
					peer.shutdown();
					RUBTClient.this.RUN = false;
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					System.exit(1);
				}
			}
		}
		
		
		
		public void pause() {
			new Request(RUBTClient.this, "stopped");
		}
	}
}
