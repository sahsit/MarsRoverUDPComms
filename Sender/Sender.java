import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
public class Sender {

    public static void main(String[] args) throws Exception {

 
        // Sender (sender_ack_port)  --DATA-->  Receiver (rcv_data_port)   
        // Receiver (rcv_data_port) --ACK--> Sender (sender_ack_port)     
    

        //java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
        //Parse first 5 args
        InetAddress rcvAddress = InetAddress.getByName(args[0]);
        int rcvDataPort = Integer.parseInt(args[1]);
        int sendAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeout = Integer.parseInt(args[4]);

        // Create a socket to send data to the receiver and receive ACKs from the receiver (using sender_ack_port)
        DatagramSocket socket = new DatagramSocket(sendAckPort);
        socket.setSoTimeout(timeout);
        
        // HANDSHAKE PROTOCOL       
        
        // Sending a SOT packet
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int timeoutCount = 0;
        long startTime = System.nanoTime();

        while (true) {
            byte[] bytes = sot.toBytes();
            DatagramPacket udp = new DatagramPacket(bytes, bytes.length, rcvAddress, rcvDataPort);
            socket.send(udp);
            //System.out.println("Sender sent SOT");

            // Waiting and getting an ACK 0
            byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE]; // making a buffer to hold the raw bytes of future incoming packets (making it max packet size)
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length); // wrapping this buffer in a datagramPacket object (the socket can only receive datagram packets, not raw bytes, so we need to wrap the buffer in a datagram packet to receive data into it)
            
            try {
                socket.receive(incoming); // writing the incoming packet's raw bytes into the buffer (the buffer is now filled with the raw bytes of the incoming packet)

                DSPacket ackPacket = new DSPacket(incoming.getData()); // wrapping the datagramPacket in a DSPacket object to parse the raw bytes into the packet's fields (type, seqNum, length, payload)

                // now, we can use ackPacket, which is the application-layer packet that we can easily work with, to check if it's the ACK we expect for the SOT we sent (type should be ACK and seqNum should be 0)
                if (ackPacket.getType() == DSPacket.TYPE_ACK && ackPacket.getSeqNum() == 0) {
                    System.out.println("ACK received for SOT");
                    timeoutCount = 0;
                    break;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout, resending SOT");
                // resend SOT packet
                timeoutCount++;
                
                if (timeoutCount >=3) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    return;
                }
            }

        }
        
        //[window_size]
        if (args.length == 6) {
            int windowSize = Integer.parseInt(args[5]);
            if (windowSize <= 0 || windowSize > 128 || windowSize % 4 != 0) {
                System.out.println("Invalid window size. Must be a multiple of 4 and <= 128.");
                socket.close();
                return;
            }

            FileInputStream fis = new FileInputStream(inputFile);
            int lastDataSeq = goBackN(socket, fis, rcvAddress, rcvDataPort, windowSize);
            fis.close();

            if (lastDataSeq == -1) {
                return;
            }

            int eotSeq = (lastDataSeq + 1) % 128;
            int result = eotProtocol(socket, rcvAddress, rcvDataPort, eotSeq);
            if (result == -1) {
                return;
            }

            long endTime = System.nanoTime();
            double seconds = (endTime - startTime) / 1_000_000_000.0;
            System.out.printf("Total transmission time: %.2f seconds%n", seconds);
            
            socket.close();
            
        } else {
            // use stop and wait
            FileInputStream fis = new FileInputStream(inputFile);
            int lastDataSeq = stopAndWait(socket, fis, rcvAddress, rcvDataPort);
            if (lastDataSeq == -1) {
                return;
            }
            fis.close();
            int eotSeq = (lastDataSeq + 1) % 128;
            int result = eotProtocol(socket, rcvAddress, rcvDataPort, eotSeq);
            if (result == -1) {
                return;
            }
            long endTime = System.nanoTime();
            double seconds = (endTime - startTime) / 1_000_000_000.0;
            System.out.printf("Total transmission time: %.2f seconds%n", seconds);
            socket.close();
        }

    }

    // SENDING PACKETS AND ACK LOGIC (FOR STOP AND WAIT)
    private static int stopAndWait(DatagramSocket socket, FileInputStream fis, InetAddress rcvAddress, int rcvDataPort) throws IOException {
        
        int seq = 1;
        int lastAcked = 0;

        // buffer to hold the chunk of data read from the file (max payload size)
        byte[] chunkBuf = new byte[DSPacket.MAX_PAYLOAD_SIZE]; 

        // loop to continuously send data packets
        while (true) { 
            // fis.read() puts what it read from the file into chunkBuf (n being the number of bytes it read)
            int n = fis.read(chunkBuf); 
            if (n == -1) { // end of file
                break;
            }

            // create a new byte array to hold just the bytes read (since n may be smaller than max_payload_size)
            byte[] payload = new byte[n]; 
            // copying the bytes read from chunkBuf into payload so the packet's payload is the correct size (instead of always being 124 bytes, which may have extra unused bytes (0s) at the end)
            // [66 23 0 0 0 0 0] -> [66 23] (if n = 2, for example)
            System.arraycopy(chunkBuf, 0, payload, 0, n);

            // creating the application layer packet with the payload we just read and the current sequence number (type is DATA)
            DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
            int timeoutCount = 0;

            // loop to wait for acks
            while (true) { 
                byte[] out = dataPkt.toBytes(); // converting the packet to raw bytes
                // wrapping the raw bytes in a datagram packet to send over the network
                DatagramPacket udp = new DatagramPacket(out, out.length, rcvAddress, rcvDataPort);
                socket.send(udp);
                System.out.println("Sent DATA packet with seq num " + seq);
                
                // Waiting and getting an ACK from receiver
                byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE]; // making a buffer to hold the raw bytes of future incoming packets (making it max packet size)
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length); // wrapping this buffer in a datagramPacket object (the socket can only receive datagram packets, not raw bytes, so we need to wrap the buffer in a datagram packet to receive data into it)
                
                try {
                    socket.receive(incoming); // writing the incoming packet's raw bytes into the buffer (the buffer is now filled with the raw bytes of the incoming packet)

                    DSPacket ackPacket = new DSPacket(incoming.getData()); // wrapping the datagramPacket in a DSPacket object to parse the raw bytes into the packet's fields (type, seqNum, length, payload)

                    // now, we can use ackPacket, which is the application-layer packet that we can easily work with, to check if it's the ACK we expect for the SOT we sent (type should be ACK and seqNum should be 0)
                    if (ackPacket.getType() == DSPacket.TYPE_ACK && ackPacket.getSeqNum() == seq) {
                        lastAcked = seq;
                        seq = (seq + 1) % 128;
                        System.out.println("Received ACK with correct seq num " + ackPacket.getSeqNum() + ", moving on to next packet with seq " + seq);
                        timeoutCount = 0;
                        break;
                    } else {
                        System.out.println("Received ACK with wrong seq num. expected " + seq + " but got " + ackPacket.getSeqNum() + ", resending packet with seq " + seq);
                        // resend packet we just sent
                    }
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout, resending packet with seq " + seq);
                        // resend packet we just sent
                        timeoutCount++;
                        if (timeoutCount >= 3) {
                            System.out.println("Unable to transfer file.");
                            socket.close();
                            return -1;
                        }
                    }
            
            }
        }
        return lastAcked;
    }

    private static int goBackN(DatagramSocket socket, FileInputStream fis, InetAddress rcvAddress, int rcvDataPort, int windowSize) throws IOException {
        
        // turn the input text into packets using the buildDataPackets method
        java.util.List<DSPacket> packets = buildDataPackets(fis);

        // last unACKed packet
        int baseIndex = 0;
        // next packet to send if it gets a 
        int nextIndex = 0;
        // the most recent sequence number of the ACK we received
        int lastAckedSeq = 0;

        int timeoutCount = 0;

        // while there are still packets to send (last unACKed packet doesn't go over the total number of packets)
        while (baseIndex < packets.size()) {

            // SENDING PACKETS 

            // check if nextIndex is < total number of packets and check if nextIndex is within the window (baseIndex + windowSize)
            while (nextIndex < packets.size() && nextIndex < baseIndex + windowSize) {
                // Send packets with choas permutation in groups of 4

                // get the remaining packets left to send
                int remaining = Math.min(4, packets.size() - nextIndex);
                // calculate the number of packets we're sending in this batch (if there are 4 or more remaining, we send 4, if there are less than 4 remaining, we just send the remaining number of packets)
                int sendCount = Math.min(remaining, baseIndex + windowSize - nextIndex);
                
                // grouping the packets to send in a list
                java.util.List<DSPacket> group = new java.util.ArrayList<>();
                for (int i = 0; i < sendCount; i++) {
                    group.add(packets.get(nextIndex + i));
                }

                // permuting the packets
                java.util.List<DSPacket> toSend = ChaosEngine.permutePackets(group);

                for (DSPacket pkt : toSend) {
                    // turn that packet into raw bytes
                    byte[] out = pkt.toBytes();
                    // wrap those raw bytes in a datagram packet
                    DatagramPacket udp = new DatagramPacket(out, out.length, rcvAddress, rcvDataPort);
                    socket.send(udp);
                }

                // move the nextIndex forward by the number of packets we just sent
                nextIndex += sendCount; 
            }

            // RECEIVING PACKETS

            // create a buffer to place future packets in
            byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
            // wrap the buffer in a datagram packet to receive incoming packets into it 
            DatagramPacket incoming = new DatagramPacket(buf, buf.length);

            try {
                socket.receive(incoming);
                DSPacket ack = new DSPacket(incoming.getData());

                // if the packet that we got is an ACK...
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    // ... get the sequence number of the ACK and update lastAckedSeq
                    int ackSeq = ack.getSeqNum();
                    lastAckedSeq = ackSeq;

                    // trying to find the ACKed packet that has the sequence number that matches the sequence number of the ACK we just received (basically trying to find our new "last unACKed packet")
                    // as long as we don't go past the total number of packets and we haven't found a sequence num match, we keep going
                    int newBase = baseIndex;
                    while (newBase < packets.size() && packets.get(newBase).getSeqNum() != ackSeq) {
                        newBase++;
                    }

                    // once we find the packet's sequence num that matches the ACK's seq num we just got, we move the baseIndex to it + 1
                    if (newBase < packets.size() && packets.get(newBase).getSeqNum() == ackSeq) {
                        baseIndex = newBase + 1;
                        System.out.println("Received ACK for seq " + ackSeq + ", sliding window to base index " + baseIndex);
                        timeoutCount = 0;
                    }
                }
            
            } catch (SocketTimeoutException e) {
                nextIndex = baseIndex; // Timeout, go back to the base index to resend all packets in the window
                timeoutCount++;
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    return -1;
                }
            }        
        }
        return lastAckedSeq;
    }

    private static java.util.List<DSPacket> buildDataPackets(FileInputStream fis) throws IOException {
        // create the list we'll use to store the packets in
        java.util.List<DSPacket> packets = new java.util.ArrayList<>();

        // make a buffer to put raw bytes in
        byte[] chunkBuf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        int seq = 1;

        // loop to read the file
        while (true) { 
            int n = fis.read(chunkBuf);
            if (n == -1) {
                break;
            }

            // create a new byte array that is only n (num of bytes we just read)
            byte[] payload = new byte[n];
            // copying the bytes read from chunkBuf into payload so the packet's payload is the correct size (instead of always being 124 bytes, which may have extra unused bytes (0s) at the end)
            System.arraycopy(chunkBuf, 0, payload, 0, n);
            // add that packet to our list of packets
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, payload));
            // update the seq num
            seq = (seq + 1) % 128;
        }

        return packets;
    }
    
    private static int eotProtocol(DatagramSocket socket, InetAddress rcvAddress, int rcvDataPort, int eotSeq) throws IOException {

        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int timeoutCount = 0;

        while (true) { 
            byte[] eotBytes = eot.toBytes();
            DatagramPacket eotUdp = new DatagramPacket(eotBytes, eotBytes.length, rcvAddress, rcvDataPort);
            socket.send(eotUdp);

            byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE]; // making a buffer to hold the raw bytes of future incoming packets (making it max packet size)
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length); // wrapping this buffer in a datagramPacket object (the socket can only receive datagram packets, not raw bytes, so we need to wrap the buffer in a datagram packet to receive data into it)

            try {
                socket.receive(incoming);
                DSPacket ack = new DSPacket(incoming.getData());

                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    System.out.println("ACK received for EOT, transmission complete");
                    break;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout, resending EOT");
                timeoutCount++;
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    return -1;
                }
            }
        }
        return eotSeq;
    }


    }