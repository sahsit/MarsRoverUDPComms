import java.io.FileOutputStream;
import java.net.*;


public class Receiver {
    public static void main(String[] args) throws Exception {

        //java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
        InetAddress sndAddress = InetAddress.getByName(args[0]);
        int sndAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int RN = Integer.parseInt(args[4]);

        //For ChaosEngine
        int ackCount = 0;

        DatagramSocket socket = new DatagramSocket(rcvDataPort);

        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);

        //For GBN
        int N = 128; //Needs to match sender
        boolean[] received = new boolean[128];
        byte[][] bufferGBN = new byte[128][];
        int[] bufferGBNLength = new int[128];

        //HANDSHAKE LOOP
        while (true) {
            System.out.println("Entered HandShake Loop");  
            //Receiving the incoming packet
            socket.receive(incomingPacket);
            DSPacket packet = new DSPacket(incomingPacket.getData());

            //If SOT packet
            if (packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0) {

                // send ACK(0) back to sender's ACK port
                DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, 0, null);
                byte[] ackBytes = ack.toBytes();
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, incomingPacket.getAddress(), sndAckPort);


                //Consulting CHaos Engine before sending
                ackCount++;
                if(ChaosEngine.shouldDrop(ackCount, RN)){
                    System.out.println("Dropped");
                } else { //Packet sent successfully
                    System.out.println("Packet Sent");
                    socket.send(ackUdp);
                    break;
                }
            }
            
        }

        //Handshake complete
        //Data transfer
        int expectedSequenceNum = 1;
        int lastAck = 0;
        FileOutputStream output = new FileOutputStream(outputFile);

        //DATA TRANSFER LOOP
        while (true) {
            System.out.println("Entered Data Transfer Loop"); 
            //Get data from sent packet
            incomingPacket.setLength(buffer.length);
            socket.receive(incomingPacket);
            DSPacket packet = new DSPacket(incomingPacket.getData());

            //CASE FOR RESENT SOT PACKET (ACK GOT LOST IN HANDSHAKE)
            if(packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0){
                System.out.println("Got a SOT Packet (RESENT ONE)");
                // send ACK(0) back to sender's ACK port
                DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, 0, null);
                byte[] ackBytes = ack.toBytes();    
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, incomingPacket.getAddress(), sndAckPort);
                //Consulting CHaos Engine before sending
                ackCount++;
                if(ChaosEngine.shouldDrop(ackCount, RN)){
                    System.out.println("Dropped");
                } else { //Packet sent successfully
                    System.out.println("Packet Sent");
                    socket.send(ackUdp);
                }
            }

            //CASE FOR DATA PACKETS
            else if(packet.getType() == DSPacket.TYPE_DATA){
                System.out.println("Got a Data Packet");
                //Get sequence number of the sent packet
                int seqNum = packet.getSeqNum();

                // //CASE FOR IN ORDER PACKETS
                // if(seqNum == expectedSequenceNum){
                //     //Write packet data to output file
                //     output.write(packet.getPayload(), 0, packet.getLength());

                //     //Increment LastAck
                //     lastAck = expectedSequenceNum;
                //     //ExpectedSequence Num runs 0-127
                //     expectedSequenceNum = (expectedSequenceNum + 1) % 128;
                // }
                // //CASE FOR OUT OF ORDER PACKETS IS DO NOTHING

                if(packetInWindow(seqNum, expectedSequenceNum, N)){
                    if(!received[seqNum]){
                        int length = packet.getLength();
                        byte[] copy = new byte[length];
                        System.arraycopy(packet.getPayload(), 0, copy, 0, length);

                        bufferGBN[seqNum] = copy;
                        bufferGBNLength[seqNum] = length;
                        received[seqNum] = true;
                    }

                    while(received[seqNum]){
                        
                    }
                }


                // send last ACK back to sender's ACK port
                DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, lastAck, null);
                byte[] ackBytes = ack.toBytes();    
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, incomingPacket.getAddress(), sndAckPort);

                //Consulting CHaos Engine before sending
                ackCount++;
                if(ChaosEngine.shouldDrop(ackCount, RN)){
                    System.out.println("Dropped");
                } else { //Packet sent successfully
                    System.out.println("Packet Sent");
                    socket.send(ackUdp);
                }
                    
                

            //CASE FOR ENDING CONNECTION (EOT)
            } else if(packet.getType() == DSPacket.TYPE_EOT) {
                System.out.println("Got a EOT Packet");

                int seqNum = packet.getSeqNum();
                DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seqNum, null);
                byte[] ackBytes = ack.toBytes();
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, sndAddress, sndAckPort);

                //Consulting CHaos Engine before sending
                ackCount++;
                if(ChaosEngine.shouldDrop(ackCount, RN)){
                    System.out.println("Dropped");
                } else { //Packet sent successfully
                    System.out.println("Packet Sent");
                    socket.send(ackUdp);
                    break;
                }

            }

        }

        //EOT Will break out of loop
        output.close();
        socket.close();
    }


    //Used to check if incoming packets are within the window and should be buffered
    private static boolean packetInWindow(int sequenceNum, int baseNum, int N){
        int distance = (sequenceNum - baseNum + 128) % 128;
        boolean result = distance < N;
        return result;
    }
}

