package com.csc445.backend.networking;

import com.csc445.backend.game.Game;
import com.csc445.backend.utils.Utils;
import com.csc445.shared.game.Player;
import com.csc445.shared.packets.*;
import com.csc445.shared.utils.AES;
import com.csc445.shared.utils.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MulticastThread implements Runnable {

    private final InetAddress group;
    private final DatagramSocket socket;

    private final Game game;
    private final Map<Integer, PlayPacket> plays;

    private int currentPlayNumber = 1;

    private final String secretKey;

    public MulticastThread() throws IOException {
        this.secretKey = AES.TEST_PASSWORD;

        this.group = InetAddress.getByName(Constants.GROUP_ADDRESS);
        this.socket = new DatagramSocket(Constants.SERVER_PORT);
        this.game = new Game();
        this.plays = new HashMap<>();
    }

    /**
     * Function to send a packet over the socket.
     *
     * @param packet packet to send to the client/s
     */
    private void sendPacket(DatagramPacket packet) {
        new Thread(() -> {
            try {
                socket.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Function that receives a packet from a client.
     *
     * @return Datagram packet from client
     */
    private DatagramPacket receivePacket() {
        try {
            final byte[] buf = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet); // we actually receive the data here
            return packet;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Function to process a a {@link com.csc445.shared.packets.JoinPacket} from a client.  If the player could be added to the game,
     * a {@link com.csc445.shared.packets.MessagePacket} that is then multicasted out to announce
     * that the user has joined the game.  If they cannot join, an {@link com.csc445.shared.packets.ErrorPacket}
     * is sent to that client stating why they cannot be added.
     *
     * @param packet Datagram packet from client
     */
    private void processJoinPacket(DatagramPacket packet) {
        System.out.println("Join packet");

        final JoinPacket j = new JoinPacket();
        j.parseSocketData(packet);
        final Player newPlayer = new Player(packet.getAddress(), packet.getPort(), j.getName());
        final boolean didAddToGame = game.addPlayerToGame(newPlayer);

        if (!didAddToGame) {
            final ErrorPacket e = new ErrorPacket(newPlayer.getName() + " | " + newPlayer.getAddress() + " already exists in the game.", packet);
            sendPacket(e.createPacket(secretKey));
        } else {
            final MessagePacket m = new MessagePacket(newPlayer.getName() + " has joined the game.");
            sendPacket(m.createPacket(group, 4446, secretKey));
        }
    }

    /**
     * Function to process a {@link com.csc445.shared.packets.PlayPacket} from a client.  It takes the play,
     * parses it, saves it in a HashMap, updates the spot on the server side state of the game, and then
     * multicasts the PlayPacket out to all other clients listening.
     *
     * @param packet Datagram packet from client
     */
    private void processPlayPacket(DatagramPacket packet) {
        System.out.println("Play packet");

        final PlayPacket p = new PlayPacket();
        p.parseSocketData(packet);
        addPlay(p);
        game.updateSpot(p.getSpot());


        sendPacket(p.createPacket(group, 4446, secretKey));
    }

    /**
     * Function to process a HeartbeatPacket from a client.  The Heartbeat Packet is used to tell if a user is still
     * connected to the game.  The client sends the Heartbeat Packet at certain intervals.  When it is received, it
     * updates the game that they are still able to send and receive packets for the game.  If they are not properly
     * connected and send a Heartbeat packet, an {@link com.csc445.shared.packets.ErrorPacket} is sent, notifying
     * them that they need to connect properly.
     *
     * @param packet Datagram packet from client
     */
    private void processHeartbeatPacket(DatagramPacket packet) {
        System.out.println("Heartbeat packet");

        final boolean heartbeatUpdated = game.updatePlayerHeartbeat(packet.getAddress());

        if (!heartbeatUpdated) {
            final ErrorPacket e = new ErrorPacket("User is not properly connected.  Please exit and try again to authenticate.", packet);
            sendPacket(e.createPacket(secretKey));
        }
    }

    /**
     * Function to process a {@link com.csc445.shared.packets.StateRequestPacket} from a client.  When a user sends a
     * State Request Packet, that means that they are missing a particular play.  The server parses out the play number
     * they are missing, finds it in the HashMap holding all the plays, and then sends it to the client.
     *
     * @param packet Datagram packet from client
     */
    private void processStateRequestPacket(DatagramPacket packet) {
        System.out.println("State request packet");

        final StateRequestPacket s = new StateRequestPacket();
        s.parseSocketData(packet);

        final PlayPacket playPacket = getPlay(s.getPlayNumber());

        sendPacket(playPacket.createPacket(packet.getAddress(), packet.getPort(), secretKey));
    }

    /**
     * Function to process invalid packets that come in.  If a client or someone else sends a packet that is not a
     * recognized packet code, then the server sends the client back an {@link com.csc445.shared.packets.ErrorPacket}
     * stating that the code is an unknown one.
     *
     * @param packet Datagram packet from client
     */
    private void processInvalidPacket(DatagramPacket packet) {
        System.out.println("Received unknown code: " + packet.getData()[0]);

        final ErrorPacket e = new ErrorPacket("Unknown packet code: " + packet.getData()[0], packet);
        sendPacket(e.createPacket(secretKey));
    }

    /**
     * Function to process packets that come in that are not encrypted with the correct AES secret key.
     *
     * @param packet Datagram packet from client
     */
    private void processWrongPassword(DatagramPacket packet) {
        System.out.println("Cannot decrypt packet from " + packet.getAddress());

        final ErrorPacket e = new ErrorPacket("Cannot decrypt packet", packet);

        sendPacket(e.createPacket(secretKey));
    }

    /**
     * Function that wraps adding plays to the play HashMap and increments the current play count.
     *
     * @param p Play packet that was received to store
     */
    private void addPlay(PlayPacket p) {
        plays.put(currentPlayNumber, p);
        currentPlayNumber++;
    }

    /**
     * Function that gets a play from the play HashMap.
     *
     * @param playNumber the play number of the play packet to get
     * @return Play packet to send to client
     */
    private PlayPacket getPlay(int playNumber) {
        return plays.get(playNumber);
    }

    /**
     * Payload of <code>Packet</code> is trimmed from offset to length of data received
     * because otherwise it would mess up the padding and make
     * {@link com.csc445.shared.utils.AES#decryptByteArray(byte[], String) throw an error.
     *
     * @param packet packet to be decrypted
     * @return decrypted payload of <code>packet</code>, or <code>null</code> if decryption fails
     */
    private byte[] decryptPacket(DatagramPacket packet) {
        try {
            return AES.decryptByteArray(Arrays.copyOfRange(packet.getData(),
                    packet.getOffset(), packet.getLength()), secretKey);
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void run() {

        System.out.println("Server started...");
        System.out.println("Mutlicast group: " + group);
        System.out.println("Password: " + secretKey);

        while (true) {
            final DatagramPacket receivedPacket = receivePacket(); // we actually receive the data here
            if (receivedPacket != null) {
                final byte[] decryptedData = decryptPacket(receivedPacket);

                if (decryptedData == null) {
                    processWrongPassword(receivedPacket);
                } else {
                    receivedPacket.setData(decryptedData);
                    switch (receivedPacket.getData()[0]) {
                        case 1: // Join packet
                            processJoinPacket(receivedPacket);
                            break;
                        case 2: // Play packet
                            processPlayPacket(receivedPacket);
                            break;
                        case 3: // Heartbeat packet
                            processHeartbeatPacket(receivedPacket);
                            break;
                        case 6: // State request packet
                            processStateRequestPacket(receivedPacket);
                            break;
                        default:
                            processInvalidPacket(receivedPacket);
                    }
                }
            }
        }
    }
}
