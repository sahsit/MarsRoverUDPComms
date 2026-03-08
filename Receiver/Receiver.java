import java.io.FileOutputStream;
import java.net.*;


public class Receiver {
    public static final int GBNWindow = 40;
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

        //Handshake complete
        //Data transfer
        int expectedSequenceNum = 1;
        //int lastAck = 0;
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
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, sndAddress, sndAckPort);
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
                int seqNum = packet.getSeqNum();

                // Packets are in order
                if(seqNum == expectedSequenceNum){
                    if(!received[seqNum]){
                        int length = packet.getLength();
                        byte[] copy = new byte[length];
                        System.arraycopy(packet.getPayload(), 0, copy, 0, length);

                        bufferGBN[seqNum] = copy;
                        bufferGBNLength[seqNum] = length;
                        received[seqNum] = true;
                    }

                    //Write them
                    while(received[expectedSequenceNum]){
                        output.write(bufferGBN[expectedSequenceNum], 0, bufferGBNLength[expectedSequenceNum]);

                        received[expectedSequenceNum] = false;
                        bufferGBN[expectedSequenceNum] = null;
                        bufferGBNLength[expectedSequenceNum] = 0;

                        expectedSequenceNum = (expectedSequenceNum + 1) % 128;
                    }
                }
                else {
                    int distance = (seqNum - expectedSequenceNum + 128) % 128;

                    //Buffer them
                    if(distance > 0 && distance < GBNWindow){
                        if(!received[seqNum]){
                            int length = packet.getLength();
                            byte[] copy = new byte[length];
                            System.arraycopy(packet.getPayload(), 0, copy, 0, length);

                            bufferGBN[seqNum] = copy;
                            bufferGBNLength[seqNum] = length;
                            received[seqNum] = true;
                        }
                    }

                    //Else not important
                }


                // send last ACK back to sender's ACK port
                int cumulativeAck = (expectedSequenceNum + 127) % 128;
                DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, cumulativeAck, null);
                byte[] ackBytes = ack.toBytes();    
                DatagramPacket ackUdp = new DatagramPacket(ackBytes, ackBytes.length, sndAddress, sndAckPort);

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
}

