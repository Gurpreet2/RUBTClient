/*
 * @author Gurpreet Pannu, Michael Norris, Priyam Patel
 */

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class Message {

	/**
	 *  The logger for the class.
	 */
	private static final Logger logger = Logger.getLogger(Message.class.getName());
	
	int length_prefix;
	byte message_id = (byte) -1;
	int[] intPayload = null;
	byte[] bytePayload = null;
	
	private static final String[] message_types = {
		"CHOKE", "UNCHOKE", "INTERESTED", "NOT_INTERESTED",
		"HAVE", "BITFIELD", "REQUEST", "PIECE", "CANCEL"
	};
	
	/**
	 * Constructor for keep-alive, choke, unchoke, interested, and uninterested messages.
	 * @param type
	 */
	public Message (byte type) {
		this.length_prefix = 1;
		this.message_id = type;
	}
	
	
	/**
	 * Constructor for bitfield messages.
	 * 
	 * @param length_prefix
	 * @param message_id
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, byte[] payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		this.bytePayload = payload;
	}
	
	
	/**
	 * Constructor for have messages.
	 * 
	 * @param length_prefix
	 * @param message_id
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, int payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		int[] intPayload = {payload};
		this.intPayload = intPayload;
	}
	
	
	/**
	 * Constructor for request and cancel messages.
	 * @param length_prefix - the length of the message
	 * @param message_id - the type of the message
	 * @param payload
	 */
	public Message (int length_prefix, byte message_id, int[] payload) {
		this.length_prefix = length_prefix;
		this.message_id = message_id;
		this.intPayload = payload;
	}
	
	
	/**
	 * Constructor for piece messages.
	 * @param index - 0-based index of the piece.
	 * @param begin - Block index within the piece.
	 * @param block - Block to send.
	 */
	public Message (int index, int begin, byte[] block) {
		this.length_prefix = 9 + block.length;
		this.message_id = (byte) 7;
		int[] intPayload = {index, begin};
		this.intPayload = intPayload;
		this.bytePayload = block;
	}
	
	
	/**
	 * This method finds out what message the peer is sending to the client, and deals with it.
	 * 
	 * @param peer
	 * @param length - length of the incoming message
	 * @throws IOException 
	 */
	public synchronized static void dealWithMessage(Peer peer) throws IOException {
		
		// If there's nothing waiting to be read, return
		if (peer.socketInput.available() == 0) return;
		
		//logger.info("Reading message from peer : " + peer.peerId);
		DataInputStream dis = peer.socketInput;
		// Read the amount of bytes incoming
		int length = dis.readInt();
		if (length < 0 || length > 131081){
			System.out.println("Invalid length " + length);
			return; // Got the 131081 from the Message.java class that was uploaded in Sakai. I also
			// understand its importance (messages can have a maximum length of 131081 bytes... at least for now).
			
		}
		// Peer sent a seemingly valid message so reset the peer timer (to keep the connection from timing out)
		peer.peerLastMessage = System.currentTimeMillis();
		
		// Message is just a keep_alive message
		if (length == 0) {
			//logger.info("Received {KEEP_ALIVE} message from Peer ID : [ " + peer.peerId + " ].");
			return;
		}
		
		// Message is not a keep alive message, get prepared for it
		int piece_index, block_offset, block_length, piece_length = peer.client.torrentInfo.piece_length;
		byte[] block;
		try {
			// Read the first byte in the message and deal with the message appropriately
			
			int type = dis.readByte();
			
			if(type < 0 || type > 8){
				System.out.println("Unknown byte index: " + type);
				System.exit(1);
			}
			//System.out.println("Byte: " + type);
			//logger.info("Received {" + message_types[type] +"} message from Peer ID : [ " + peer.peerId + " ].");
			if(type != 4){
				System.out.println("Received {" + message_types[type] +"} message from IP : [ " + peer.peerIp
						+ " ].");
			}
			
			switch (type) {
				case 0:
					peer.peerChoking = true;
					break;
				case 1:
					peer.peerChoking = false;
					// When the peer choked, he dropped all of the requests, so have to resend them
					while (!peer.requestedQueue.isEmpty()) {
						peer.requestSendQueue.put(peer.requestedQueue.take());
					}
					break;
				case 2:
					peer.peerInterested = true;
					if (peer.amChoking && peer.client.numOfUnchokedPeers < peer.client.MAX_UNCHOKED_PEERS) {
						peer.sendMessage(createUnchoke()); //TODO fix, because peer can get out of choke by sending interesteds
		        		peer.client.choked_peers.remove(peer);
		        		peer.client.numOfUnchokedPeers++;
		        		peer.client.unchoked_peers.add(peer);
					}
					break;
				case 3:
					peer.peerInterested = false;
					if (!peer.amInterested || peer.client.amSeeding)
						peer.shutdown();
					break;
				case 4:
					//Message is a have message
					//First, read the piece_index of the have message
					piece_index = dis.readInt();
					//Set value of the peerhavearray to true
					if (!peer.peerHaveArray[piece_index]) {
						peer.peerHaveArray[piece_index] = true;
						peer.peerHaveArraySize++;
						if (peer.peerHaveArraySize != peer.client.numOfPieces)
							peer.percentPeerHas = peer.peerHaveArraySize * 100 / peer.client.numOfPieces;
						else
							peer.percentPeerHas = 100;
					} else
						break;
					//create a new request message to get the piece if client doesn't have it
					if (!peer.client.havePieces[piece_index]) {
						// Send interested and unchoke since its a piece you want
						if (!peer.sentGreetings) {
				        	peer.sendMessage(createInterested());
				        	if (peer.amChoking && peer.client.numOfUnchokedPeers < peer.client.MAX_UNCHOKED_PEERS) {
				        		peer.sendMessage(createUnchoke());
				        		peer.client.choked_peers.remove(peer);
				        		peer.client.numOfUnchokedPeers++;
				        		peer.client.unchoked_peers.add(peer);
				        	}
				        	peer.sentGreetings = true;
						}
						peer.wantFromPeer++;
						Message message = null;
						if (piece_index == peer.client.numOfPieces - 1){
							message = createRequest(piece_index, 0, peer.client.torrentInfo.file_length % piece_length);
						}
						else{
							message = createRequest(piece_index, 0, peer.client.torrentInfo.piece_length);
						}
						peer.requestSendQueue.add(message);
						// Rarest piece first
						for (Peer peer1 : peer.client.neighboring_peers) {
							if (peer.peerId.equals(peer1.peerId)) continue;
							if (peer1.requestSendQueue.contains(message)) {
								//peer1.requestSendQueue.remove(message);
								peer1.requestSendQueue.put(message);
							}
						}
					}
					break;
				case 5:
					//Message is a bitfield message (and therefore the peer is not a bad torrent controller)
					peer.badTorrentController = false;
					//Store the bitfield in the peer
					byte[] peerBitfield = new byte[length - 1];
					dis.readFully(peerBitfield);
					peer.peerBitfield = peerBitfield;
					if (peerBitfield.length != (((double)peer.client.numOfPieces)/8 == (double)(peer.client.numOfPieces/8) ?
							peer.client.numOfPieces/8 : peer.client.numOfPieces/8 + 1)) {
						System.out.println("Incorrect bitfield size from Peer "
								+ "ID : [ " + peer.peerId + "] with IP : [ " + peer.peerIp
								+ " ].");
						peer.shutdown();
						break;
					}
					for (int i = 0; i < peer.client.numOfPieces; i++) {
						boolean isBitSet = MyTools.isBitSet(peerBitfield, i);
						if (isBitSet) {
							peer.peerHaveArray[i] = true;
							peer.peerHaveArraySize++;
							if (peer.peerHaveArraySize != peer.client.numOfPieces)
								peer.percentPeerHas = peer.peerHaveArraySize * 100 / peer.client.numOfPieces;
							else
								peer.percentPeerHas = 100;
						}
						if (isBitSet && !peer.client.havePieces[i]) {
							//Send unchoke and interested if peer has something I want
							if (!peer.sentGreetings) {
							    peer.sendMessage(createInterested());
					        	if (peer.amChoking && peer.client.numOfUnchokedPeers < peer.client.MAX_UNCHOKED_PEERS) {
					        		peer.sendMessage(createUnchoke());
					        		peer.client.choked_peers.remove(peer);
					        	}
							    peer.sentGreetings = true;
							}
							peer.wantFromPeer++;
							Message message = null;
							if (i == peer.client.numOfPieces - 1){
								message = createRequest(i, 0, peer.client.torrentInfo.file_length % piece_length);
								//System.out.println("asdf");
							}
							else{
								message = createRequest(i, 0, peer.client.torrentInfo.piece_length);
							//	System.out.println("fdsa");
							}
							// Rarest piece first
							peer.requestSendQueue.add(message);
							try{
								for (Peer peer1 : peer.client.neighboring_peers) {
									if (peer.peerId.equals(peer1.peerId)) continue;
	
									if (peer1.requestSendQueue.contains(message)) {
										peer1.requestSendQueue.remove(message);
										peer1.requestSendQueue.put(message);
									}
								}
							}
							catch(Exception e){
								System.err.println("EXCEPTION ERROR: " + e);
							}
						}
					}
					break;
				case 6:
					//Message is a request message (if have it, send it)
					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block_length = dis.readInt();
					// If choking, ignore the message
					if (peer.amChoking) break;
					if (peer.client.havePieces[piece_index]) {
						byte[] payload = new byte[block_length];
						peer.client.theDownloadFile.seek(piece_index * piece_length);
						peer.client.theDownloadFile.read(payload, 0, payload.length);
						peer.pieceSendQueue.offer(createPiece(piece_index, block_offset, payload));
					}
					break;
				case 7:
					//Message is a piece message

					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block = new byte[length-9];
					dis.readFully(block);
					
					if(peer.client.havePieces[piece_index]){
						System.out.println("Client already has piece, don't need it");
						break;
					}
					//System.out.println("PIECE BLOCK: " + piece_index + " " + block_offset + " " + Arrays.toString(block));
					//System.exit(1);
					// Verify the piece message's hash
					MessageDigest md = MessageDigest.getInstance("SHA");
					byte[] hash = null;
					hash = md.digest(block);
					boolean cray = true;
					byte[] checkWith = peer.client.torrentInfo.piece_hashes[piece_index].array();
					for (int i = 0; i < checkWith.length; i++)
						if (checkWith[i] != hash[i]) cray = false;
					if (cray) {
						peer.writing = true;
						peer.client.theDownloadFile.seek(piece_index * piece_length);
						peer.client.theDownloadFile.write(block, 0, block.length);
						peer.client.bytesLeft -= block.length;
						peer.client.bytesDownloaded += block.length;
						peer.client.havePieces[piece_index] = true;
						peer.client.numOfHavePieces++;
						peer.downloadedFromPeer++;
						peer.bytesFromPeer += block.length;
						peer.requestsSent--;
						peer.requestedQueue.remove(createRequest(piece_index, block_offset, block.length));
						peer.bytes_received += block.length; // For throttle
						peer.fileBytesUploaded += block.length;
						for (Peer peer1 : peer.client.neighboring_peers)
							peer1.haveSendQueue.offer(createHave(piece_index), 2500, TimeUnit.MILLISECONDS);
					} else {
						System.out.println("Requesting piece again");
						peer.requestSendQueue.put(createRequest(piece_index, block_offset, length));
					}
					peer.writing = false;
					break;
				case 8: // CANCEL Message
					piece_index = dis.readInt();
					block_offset = dis.readInt();
					block_length = dis.readInt();
					int[] c_payload = {piece_index, block_offset};
					for(Message m : peer.pieceSendQueue){
						if(m.intPayload == c_payload){
							System.out.println("Removed message from piece send queue after recv CANCEL");
							peer.pieceSendQueue.remove(m);
							break;
						}	
					}
				default:
					System.out.println("Received unexpected message from peer, disconnecting peer");
					peer.shutdown();
					break;
			}
		} catch(EOFException e) {
			logger.info("EOF exception from Peer : " + peer.peerId);
			peer.shutdown();
		} catch(IOException e) {
			logger.info("IO exception from Peer : " + peer.peerId);
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			logger.info("NoSuchAlgorithm exception from Peer : " + peer.peerId);
			e.printStackTrace();
		} catch (InterruptedException e) {
			logger.info("Interrupted exception from Peer : " + peer.peerId);
			e.printStackTrace();
		}
	}
	
	
	/**
	 * This method verifies that the peer id received in a handshake message matches with the peer id that was received in the
	 * tracker response that corresponds with the current peer's ip.
	 * 
	 * @param handshake -> The handshakeMessage that the peer sent
	 * @param peer_id -> The peer id that should be in the handshake method
	 * @return -> True if the peer id matches with the one in the message
	 */
	
	public static boolean verifyHandshake(byte[] msg_to_peer, byte[] peer_response){
		if(msg_to_peer.length!=68 || peer_response.length != 68){
			System.err.println("Incorrect length of response!");
			return false;
		}
		
		byte[] sent_infoHash = Arrays.copyOfRange(msg_to_peer, 28, 47);
		byte[] received_infoHash = Arrays.copyOfRange(peer_response, 28, 47);
		if (Arrays.equals(sent_infoHash, received_infoHash)) {
			return true;
		}
		else{
			return false;
		}
	}
	
	
	/**
	 * Create a keep-alive message.
	 * @return 
	 */
	public static Message createKeepAlive() {
		return new Message((byte) -1);
	}
	
	
	/**
	 * Create a choke message.
	 * @return
	 */
	public static Message createChoke() {
		return new Message((byte) 0);
	}
	
	
	/**
	 * Create an unchoke message.
	 * @return
	 */
	public static Message createUnchoke() {
		return new Message((byte) 1);
	}
	
	
	/**
	 * Create an interested message.
	 * @return
	 */
	public static Message createInterested() {
		Message message = new Message((byte) 2);
		return message;
	}
	
	
	/**
	 * Create an uninterested message.
	 * @return
	 */
	public static Message createNotInterested() {
		return new Message((byte) 3);
	}
	
	
	/**
	 * Create a have message.
	 * @param piece_index - 0-based index of the piece I have (out of total number of pieces).
	 * @return
	 */
	public static Message createHave(int piece_index) {
		return new Message(5, (byte) 4, piece_index);
	}
	
	
	/**
	 * Create a bitfield message.
	 * @param client
	 * @return
	 */
	public static Message createBitfield(RUBTClient client) {
		if (client.havePieces == null) return null;
		boolean clientHasOneOrMorePiece = false; // Because I can
		
		// If the number of pieces is divisible by 8, make an array of size numOfPieces/8, else make one of size numOfPieces/8 + 1
		byte[] bitfield = 
				new byte[((double)client.numOfPieces)/8 == (double)(client.numOfPieces/8) ?
						client.numOfPieces/8 : client.numOfPieces/8 + 1];
		for (int i = 0; i < client.numOfPieces; i++) {
			if (client.havePieces[i]) {
				MyTools.setBit(bitfield, i);
				clientHasOneOrMorePiece = true;
			}
		}
		if (clientHasOneOrMorePiece) 
			return new Message(bitfield.length + 1, (byte) 5, bitfield);
		else
			return null;
	}
	
	
	/**
	 * Create a request message.
	 * @param index - 0-based index of piece
	 * @param offset - 0-based index of block
	 * @param length - length of block
	 * @return
	 */
	public static Message createRequest(int index, int offset, int length) {
		int[] payload = {index, offset, length};
		//return new Message(13, (byte) 6, payload);
		Message message = new Message(13, (byte) 6, payload);
		return message;
	}
	
	
	/**
	 * Create a piece message.
	 * @param client
	 * @return
	 */
	public static Message createPiece(int piece_index, int block_offset, byte[] payload) {
		return new Message(piece_index, block_offset, payload);
	}
	
	
	public byte[] createCancel(RUBTClient client) {
		byte[] biteArray = null;
		// TODO
		return biteArray;
	}
}
