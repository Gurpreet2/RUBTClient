package GivenTools;

import java.io.*;

import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import GivenTools.Peer;
import GivenTools.Tracker;

public class RUBTClient extends Thread{

	public static final char[] HEX_CHARS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 
		'A', 'B', 'C', 'D', 'E', 'F'};
	
	protected static boolean client_running = true;
	protected Tracker tracker;
	protected Thread ClientThread;
	protected TorrentInfo torr_info;
	protected int downloaded, uploaded, left;
	protected int totalPieces, piece_length, file_length;
	protected int peersUnchoked = 0;
	protected int maxPeers = 3;
	protected byte[] bitfield;
	protected boolean am_seed = false;
	protected String client_id = null;
	protected ArrayList<Peer> peerHistory;
	protected ArrayList<Peer> peers;
	protected ArrayList<Piece> piece_arr;
	private RandomAccessFile outFile;
	private ConcurrentLinkedQueue<Message> messages = new ConcurrentLinkedQueue<Message>();
	
	public RUBTClient(TorrentInfo torr, Tracker track, RandomAccessFile filename, String client_peer_id) {
		System.out.println("Creating: Client Thread");
		this.tracker = track;
		this.torr_info = torr;
		this.downloaded = 0;
		this.uploaded = 0;
		this.client_id = client_peer_id;
		this.file_length = torr.file_length;
		this.bitfield = new byte[this.torr_info.piece_hashes.length];
		this.piece_arr = generatePieces();
		if(filename == null){
			System.out.println("Output file does not exist. Starting a new session.");
			this.bitfield = new byte[this.torr_info.piece_hashes.length];
			this.left = torr.file_length;
			//updateLeft();
		}
		else{
			System.out.println("Output file exists! Resuming previous session..");
			this.left = torr.file_length;
			//updateLeft();
		}
		
	}
	
	


	
	private boolean amInterested(Peer peer) {
		if (this.left == 0) {
			return false;
		}

		// Inspect bitfield
		//System.out.println("Total pieces: " + this.totalPieces);
		for (int pieceIndex = 0; pieceIndex < this.totalPieces; pieceIndex++) {
			if (!Utility.isSetBit((this.bitfield), pieceIndex)
					&& Utility.isSetBit((peer.bitfield), pieceIndex)) {
				return true;
			}
		}
		return false;
	}
	
	
	private void StopClient(){
		if(ClientThread.isAlive()){
			//client.dropConnection
		}
	}
	
	public  boolean verifyPiece(int index, byte[] piece){
		
		ByteBuffer bb = torr_info.piece_hashes[index];
		
		MessageDigest SHA1 = null;
		
		try{
			SHA1 = MessageDigest.getInstance("SHA-1");
		}
		catch(NoSuchAlgorithmException e){
			System.out.println(e);
		}
		
		SHA1.update(piece);
		byte[] pieceHash = SHA1.digest();
		
		if(Arrays.equals(pieceHash, bb.array())){
			return true;
		}
		else{
			return false;
		}

	}
	private void setBitfieldBit(final int bit) {
		byte[] tempBitfield = this.bitfield;
		tempBitfield = Utility.setBit(tempBitfield, bit);
		this.bitfield =  tempBitfield;
	}
	private void resetBitfieldBit(final int bit) {
		byte[] tempBitfield = this.bitfield;
		tempBitfield = Utility.resetBit(tempBitfield, bit);
		this.bitfield = tempBitfield;
	}
	private void setBitfield() throws IOException {
		final int bytes = (int) Math.ceil(this.totalPieces / 8.0);
		this.bitfield = new byte[bytes];

		for (int pieceIndex = 0; pieceIndex < this.totalPieces; pieceIndex++) {
			byte[] temp;
			if (pieceIndex == this.totalPieces - 1) {
				// Last piece
				temp = new byte[this.file_length % this.piece_length];
			} else {
				temp = new byte[this.piece_length];
			}
			this.outFile.read(temp);
			if (this.verifyPiece(pieceIndex, temp)) {
				this.setBitfieldBit(pieceIndex);
				this.left = this.left - temp.length;
			} else {
				this.resetBitfieldBit(pieceIndex);
			}
		}
	}
	
	public int getPieceIndex(){
		int i = 0;
		for(byte b : bitfield){
			if(b == 0){
				return i;
			}
			i++;
		}
		return -1;
	}
	
	public void run(){
		// Create URL, send announce.
		URL trackerURL = this.tracker.create_url(this.torr_info, this.torr_info.announce_url.toExternalForm(), this.client_id, this.uploaded, this.downloaded, this.left, "started");	
		if(trackerURL == null){
			System.err.println("Error creating tracker url");
			System.exit(1);
		}
		this.tracker.tracker_url = trackerURL;
		
		int min_interval = this.tracker.tracker_minInterval * 1000;
		int interval = this.tracker.tracker_interval * 1000;

		// If min interval not set, then set it to half of interval time.		
		min_interval = min_interval == 0 ? interval/2 : min_interval;
		long last_announceTime = System.currentTimeMillis();
		
		int index = 0, offset, length;
		byte[] block;
		while(client_running){
			if ((System.currentTimeMillis() - last_announceTime) >= (min_interval)) {
				this.tracker.announce();
				System.out.println("Sending tracker announce. Client status : ");
				last_announceTime = System.currentTimeMillis();
			}
			
			try {
				/* Change into this:
				 * Message msg = message_queue.dequeue(); // also change msg var name
				 * Peer peer = msg.getPeer();
				 */
				//System.out.println("here");
				Message msg = this.messages.poll();
				if(msg == null){
					continue;
				}
				Peer peer = msg.getPeer();
				System.out.println("Recv: " + msg + " from peer " + peer.getId());
				switch(msg.getMessageID()){
				case -1: // Keep Alive
					peer.sendMessage(Message.keepAliveMessage());
					break;
				case 0: // Received Choke
					peer.localChoked = true;
					break;
				case 1: // Received Unchoke
					peer.localChoked = false;   
					if(this.amInterested(peer)){
						System.out.println("HEHE I AM INTEREST");
						peer.sendMessage(Message.requestMessage(0, 0, 16384));
					}
					break;
				case 2: // Received Interested
					peer.remoteInterested = true;

				/*	if (this.peersUnchoked < this.maxPeers) {
						this.peersUnchoked++;
						peer.sendMessage(Message.unchokeMessage());
						peer.remoteChoked = false;
					} else {
						peer.sendMessage(Message.chokeMessage());
						peer.remoteChoked = true;
					}*/
					break;
				case 3: // Received uninterested
					// Update internal state
					peer.remoteInterested = false;
					peer.sendMessage(Message.keepAliveMessage());
					break;
				case 4: // Received have
					if (peer.getBitfield() == null) {
						peer.allocateBitfield(this.totalPieces);
					}
					index = msg.getPayload()[0];
					peer.setBitfieldBit(index);
					peer.sendMessage(Message.interestedMessage());
					//peer.sendMessage(Message.requestMessage(index, 0, 16384));

					/*peer.setLocalInterested(this.amInterested(peer));
					if (!peer.localChoked && peer.localInterested) {
						peer.sendMessage(Message.interestedMessage());
						peer.localInterested = true;
					} else {
						peer.sendMessage(Message.keepAliveMessage());
					}*/
					break;
				case 5: // Received Bitfield
					peer.setBitfield(msg.getPayload()); //bitfield
					peer.sendMessage(Message.interestedMessage());
					peer.setLocalInterested(true); //this.amInterested(peer)
					/*if (!peer.localChoked && peer.localInterested) {
						peer.sendMessage(Message.interestedMessage());
					} else if (peer.localInterested) {
						peer.sendMessage(Message.interestedMessage());
					} else {
						peer.sendMessage(Message.keepAliveMessage());
					}*/
					break;
				
				case 6: // Received request
					 index = msg.getPayload()[0];
					 offset = msg.getPayload()[1];
					 length = msg.getPayload()[2];
					// Check that we have the piece
					if (Utility.isSetBit(this.bitfield,	index)) {
						// Send the block
						block = new byte[msg.getPayload()[2]];
						System.arraycopy(Utility.getFileInBytes(this.outFile),offset, block, 0,length);

						peer.sendMessage(Message.pieceMessage(index, offset, block));
					} else {
						// Peer is misbehaving, choke
						peer.sendMessage(Message.chokeMessage());
					}

					break;
				case 7: // Received Piece message
					index = msg.getPayload()[0];
					offset = msg.getPayload()[1];
					block = new byte[msg.getPayload().length -2];
					
					for(int i = 2; i < msg.getPayload().length; i++){
						block[i-2] = msg.getPayload()[i];
					}
					// Updated downloaded
					this.downloaded += block.length;

					// Verify piece
					if (this.verifyPiece(index,	block)) {
						// Write piece
						System.out.println("Writing piece [pieceIndex=" + index + "] to file");

						this.outFile.seek(index * this.piece_length);
						this.outFile.write(block);
						this.setBitfieldBit(index);

						// Recalculate amount left to download
						this.left = this.left - block.length;
						// For some reason left can go below 0...
						if (this.left < 0) {
							this.left = 0;
						}

						// Notify peers that the piece is complete
						this.announceHave(index);
					} else {
						// Drop piece
						this.resetBitfieldBit(index);
					}


					if (!peer.localChoked && peer.localInterested) {
						System.out.println("Should do something");
						//this.chooseAndRequestPiece(peer);
					} else {
						peer.sendMessage(Message.keepAliveMessage());
					}
					break;
				default:
					System.err.println("Error: Peer message's ID could not be identified.");
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	}
	
	
	private ArrayList<Piece> generatePieces() {
		ArrayList<Piece> piece_array = new ArrayList<Piece>();
		int total = this.torr_info.file_length;
		for (int i = 0; i < this.torr_info.piece_hashes.length; i++) {
			piece_array.add(new Piece(i, Math.min(total, this.torr_info.piece_length)));
			total -= this.torr_info.piece_length;
		}
		//this.piecesHad = new BitSet(al.size());
		return piece_array;
	}
	
	private Piece choosePiece(Peer pr) {
		int[] pieceRanks = new int[this.totalPieces];

		for(Piece piece : this.piece_arr) {
			if (piece.getState() == Piece.PieceState.INCOMPLETE && pr.canGetPiece(piece.getIndex())) {
				pieceRanks[piece.getIndex()] = 0;
			} else {
				pieceRanks[piece.getIndex()] = -1;
			}
		}

		for (Peer peer : this.peers) {
			for (Piece piece : this.piece_arr) {
				if (peer.canGetPiece(piece.getIndex()) && pieceRanks[piece.getIndex()] != -1) {
					pieceRanks[piece.getIndex()]++;
				}
			}
		}

		int leastPieceIndex = -1, leastPieceValue = -1;

		for (int i = 0; i < pieceRanks.length; i++) {
			if (leastPieceIndex == -1 && pieceRanks[i] != -1) {
				leastPieceIndex = i;
				leastPieceValue = pieceRanks[i];
			}
			else if (leastPieceValue != -1 && leastPieceValue > pieceRanks[i] && pieceRanks[i] != -1) {
				leastPieceIndex = i;
				leastPieceValue = pieceRanks[i];
			}
		}
		if (leastPieceIndex == -1)
			return null;

		return this.piece_arr.get(leastPieceIndex);
	}
	
	
	private void announceHave(int index) {
		for(Peer p : this.peers){
			try{
				p.sendMessage(Message.haveMessage(index));
			}
			catch(Exception e){
				System.out.println(e);
			}
		}
		
	}

	public static void main(String[] args) throws FileNotFoundException, BencodingException {
		File torrent_file = null;
		File output_file = null;
		TorrentInfo torr = null;
		Socket tracker_sock = null;
		String announceURL = "";
		String client_peer_id = "";
		//Check if number of arguments are correct
		if(args.length != 2){
			System.err.println("Invalid number of arguments.");
			return;
		}
	
		try{
			torrent_file = new File(args[0]);
			output_file = new File(args[1]);
		}
		catch (NullPointerException e){
			System.err.println(e);
			return;
		}
		
		// gets torrentinfo obj torr 
		torr = getTorrentInfo(torrent_file);
		//generate a random client id
		client_peer_id = Peer.Generate_RandomID();
		// get announce url
		announceURL = torr.announce_url.toExternalForm();
		
		RUBTClient client = null; 
		Tracker track = new Tracker(tracker_sock, null, torr);
		try {
			RandomAccessFile file = new RandomAccessFile(output_file,"rw");

			/* The File exists */
			client = new RUBTClient(torr, track, file, client_peer_id);
			client.outFile = file;

		} catch (FileNotFoundException e) {
			/* The File does not exist */
			client = new RUBTClient(torr, track, null, client_peer_id);
		}
		// sets up connection to tracker and sends http get request
		URL trackerURL = track.create_url(torr, announceURL, client_peer_id, 0, 0, client.left, "started");	
		if(trackerURL == null){
			System.err.println("Error creating tracker url");
			System.exit(1);
		}
		track.tracker_url = trackerURL;
		
		ArrayList<Peer> rubt_peers = track.send_get_tracker(client);  //returns rubt peers
		if(rubt_peers == null){
			System.err.println("ERROR: RUBT peers list is empty");
			System.exit(1);
		}
		
		System.out.println("rubt-peer ip and ids");
		//debug
		for(Peer peer : rubt_peers){
			System.out.println(peer.ip + " : " + peer.peer_id);
		}	
		
		client.totalPieces = torr.piece_hashes.length;
		// Opens a connection with the RUBT peer(s) 
		Peer.connect_peers(rubt_peers, torr);
		
		client.start();		

	}
		
	

	public static TorrentInfo getTorrentInfo(File torr_file){
		FileInputStream file_stream = null;
		TorrentInfo torr_info = null;
		byte [] buff = new byte[(int) torr_file.length()];
		
		try{
			file_stream = new FileInputStream(torr_file);
		} catch (FileNotFoundException e) 
		{
			System.err.println("ERROR: The torrent file was not found");
			System.exit(1);
		}
		
		
		try{
			file_stream.read(buff, 0, buff.length);
		} catch (IOException e) {
			System.err.println("Error reading the torrent file");
			System.exit(1);
		}
		
		try{
			torr_info = new TorrentInfo(buff);
		} catch (Exception e) {
			System.err.println("Error loading torrent file into torrent file object");
			System.exit(1);
		}
		
		try{
			file_stream.close();
		} catch (IOException e) {
			System.err.println(e);
		}
		
		return torr_info;
	}
	
	
	
	/* Converts byte array to hex string */
	public static String toHexString(byte[] bytes) {
		if (bytes == null) {
			return null;
		}

		if (bytes.length == 0) {
			return "";
		}

		StringBuilder HexOutput = new StringBuilder(bytes.length * 3);
		for(int i=0; i < bytes.length; i++){
			HexOutput.append('%').append(HEX_CHARS[(byte) (bytes[i] >> 4)& 0x0f]).append(HEX_CHARS[(byte) (bytes[i] & 0x0f)]);
		}
		
		return HexOutput.toString();
	}

	
	public void recvMessage(Message msg){
		this.messages.add(msg);
	}
	
		
	}
	
