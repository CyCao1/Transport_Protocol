import java.util.*;
import java.io.*;

public class StudentNetworkSimulator extends NetworkSimulator
{
    /*
     * Predefined Constants (static member variables):
     *
     *   int MAXDATASIZE : the maximum size of the Message data and
     *                     Packet payload
     *
     *   int A           : a predefined integer that represents entity A
     *   int B           : a predefined integer that represents entity B
     *
     * Predefined Member Methods:
     *
     *  void stopTimer(int entity):
     *       Stops the timer running at "entity" [A or B]
     *  void startTimer(int entity, double increment):
     *       Starts a timer running at "entity" [A or B], which will expire in
     *       "increment" time units, causing the interrupt handler to be
     *       called.  You should only call this with A.
     *  void toLayer3(int callingEntity, Packet p)
     *       Puts the packet "p" into the network from "callingEntity" [A or B]
     *  void toLayer5(String dataSent)
     *       Passes "dataSent" up to layer 5
     *  double getTime()
     *       Returns the current time in the simulator.  Might be useful for
     *       debugging.
     *  int getTraceLevel()
     *       Returns TraceLevel
     *  void printEventList()
     *       Prints the current event list to stdout.  Might be useful for
     *       debugging, but probably not.
     *
     *
     *  Predefined Classes:
     *
     *  Message: Used to encapsulate a message coming from layer 5
     *    Constructor:
     *      Message(String inputData):
     *          creates a new Message containing "inputData"
     *    Methods:
     *      boolean setData(String inputData):
     *          sets an existing Message's data to "inputData"
     *          returns true on success, false otherwise
     *      String getData():
     *          returns the data contained in the message
     *  Packet: Used to encapsulate a packet
     *    Constructors:
     *      Packet (Packet p):
     *          creates a new Packet that is a copy of "p"
     *      Packet (int seq, int ack, int check, String newPayload)
     *          creates a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and a
     *          payload of "newPayload"
     *      Packet (int seq, int ack, int check)
     *          chreate a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and
     *          an empty payload
     *    Methods:
     *      boolean setSeqnum(int n)
     *          sets the Packet's sequence field to "n"
     *          returns true on success, false otherwise
     *      boolean setAcknum(int n)
     *          sets the Packet's ack field to "n"
     *          returns true on success, false otherwise
     *      boolean setChecksum(int n)
     *          sets the Packet's checksum to "n"
     *          returns true on success, false otherwise
     *      boolean setPayload(String newPayload)
     *          sets the Packet's payload to "newPayload"
     *          returns true on success, false otherwise
     *      int getSeqnum()
     *          returns the contents of the Packet's sequence field
     *      int getAcknum()
     *          returns the contents of the Packet's ack field
     *      int getChecksum()
     *          returns the checksum of the Packet
     *      int getPayload()
     *          returns the Packet's payload
     *
     */

    /*   Please use the following variables in your routines.
     *   int WindowSize  : the window size
     *   double RxmtInterval   : the retransmission timeout
     *   int LimitSeqNo  : when sequence number reaches this value, it wraps around
     */

    public static final int FirstSeqNo = 0;
    private int WindowSize;
    private double RxmtInterval;
    private int LimitSeqNo;


    // Add any necessary class variables here.  Remember, you cannot use
    // these variables to send messages error free!  They can only hold
    // state information for A or B.
    // Also add any necessary methods (e.g. checksum of a String)

    // For A
    private int seqNum = 0;
    private int nextExpectAck = 0;
    private static int bufferSize = 50;
    private LinkedList<Packet> senderBuffer = new LinkedList<Packet>();
    private LinkedList<Packet> senderWindow = new LinkedList<Packet>();

    // For B
    private int nextExpectSeqNum = 0;
    private Packet lastRcvPacket;
    private LinkedList<Packet> receiverWindow = new LinkedList<Packet>();

    // For statistics
    private int originalTransCnt = 0;
    private int reTransCnt = 0;
    private int deliveredPktCnt = 0;
    private int ackedPktCnt = 0;
    private int corruptedPktCnt = 0;

    private Map<Integer,Double> rttMap = new HashMap<Integer,Double>();
    private double rttSum = 0.0;
    private int rttCnt = 0;

    private Map<Integer,Double> cmmMap = new HashMap<Integer,Double>();
    private double cmmSum = 0.0;
    private int cmmCnt = 0;


    // This is the constructor.  Don't touch!
    public StudentNetworkSimulator(int numMessages,
                                   double loss,
                                   double corrupt,
                                   double avgDelay,
                                   int trace,
                                   int seed,
                                   int winsize,
                                   double delay)
    {
        super(numMessages, loss, corrupt, avgDelay, trace, seed);
        WindowSize = winsize;
        LimitSeqNo = winsize*2; // set appropriately; assumes SR here!
        RxmtInterval = delay;
    }


    private int computeCheckSum(Packet pkt) {
        int checkSum = pkt.getAcknum() + pkt.getSeqnum();
        String msg = pkt.getPayload();
        for(int i = 0; i < msg.length(); i++){
            checkSum += (int)msg.charAt(i);
        }
        return checkSum;
    }

    private void refillSenderWindow() {

        while(senderWindow.size() < WindowSize && senderBuffer.size() > 0){
            Packet pktToSend = senderBuffer.remove();
            senderWindow.add(pktToSend); // put this packet into the sender window
            sendPkt(pktToSend); // send this packet to layer3, it is now outstanding and unAcked
            originalTransCnt++;
        }
    }

    private void sendPkt(Packet pkt) {
        if (rttMap.putIfAbsent(pkt.getSeqnum(), getTime()) != null)
            rttMap.replace(pkt.getSeqnum(), Double.NaN);
        cmmMap.putIfAbsent(pkt.getSeqnum(), getTime());
        toLayer3(A, pkt);
        stopTimer(A);
        startTimer(A, RxmtInterval);
    }

    private void updateStatistics(int pktSeqNum) {
        updateRttMap(pktSeqNum);
        updateCmmMap(pktSeqNum);
    }

    private void updateRttMap (int pktSeqNum) {
        double timeRegisterInRttMap = rttMap.get(pktSeqNum);
        if (!Double.isNaN(timeRegisterInRttMap) && timeRegisterInRttMap>0){
            rttSum += getTime() - timeRegisterInRttMap;
            rttCnt++;
        }
    }

    private void updateCmmMap (int pktSeqNum) {
        double timeRegisterInCmmMap = cmmMap.get(pktSeqNum);
        cmmSum += getTime() - timeRegisterInCmmMap;
        cmmCnt++;
    }


    // This routine will be called whenever the upper layer at the sender [A]
    // has a message to send.  It is the job of your protocol to insure that
    // the data in such a message is delivered in-order, and correctly, to
    // the receiving upper layer.
    protected void aOutput(Message message) {
        System.out.println("aOutput: Start");
        Packet sndPkt = new Packet(seqNum, -1, 0, message.getData());
        sndPkt.setChecksum(computeCheckSum(sndPkt));
        senderBuffer.add(sndPkt);
        seqNum++;

        // if sender window allows, put sndPkt into the sender window
        refillSenderWindow();
        System.out.println("aOutput: End");
    }

    // This routine will be called whenever a packet sent from the B-side
    // (i.e. as a result of a toLayer3() being done by a B-side procedure)
    // arrives at the A-side.  "packet" is the (possibly corrupted) packet
    // sent from the B-side.
    protected void aInput(Packet rcvPkt) {
        System.out.println("aInput: Start");

        // Packet is corrupted
        if (rcvPkt.getChecksum() != computeCheckSum(rcvPkt)){
            System.out.println("aInput: A got a corrupted ACK from B");
            corruptedPktCnt++;
            System.out.println("aInput: End");
            return;
        }

        // Sender window is empty, wait for call from upper layer
        if (senderWindow.size() == 0) {
            //-------
            updateRttMap(rcvPkt.getSeqnum());
            //-------
            System.out.println("aInput: A got a duplicated ACK from, " + rcvPkt);
            System.out.println("A's sender window is now empty, no unACK'ed packet left");
            System.out.println("This just received packet is an ACK for the previously resent pkt" + rcvPkt.getSeqnum());
            System.out.println("Wait for messages from layer5");
            stopTimer(A);
            System.out.println("aInput: End");
            return;
        }

        int minExpectedAckNum = senderWindow.getFirst().getSeqnum();
        int rcvPktAckNum = rcvPkt.getAcknum();
        // check if an expected ack is received
        if (rcvPktAckNum >= minExpectedAckNum) {
            if (rcvPktAckNum == minExpectedAckNum) {
                System.out.println("aInput: A got an ACK from B, packet is: " + rcvPkt);
                System.out.println("aInput: Sliding window by 1");
            }
            else {
                System.out.println("aInput: A got a cumulative ACK from B, packet is: " + rcvPkt);
                System.out.println("ACK for pkt" + minExpectedAckNum + " to pkt" + rcvPktAckNum);
                System.out.println("aInput: Sliding window by " + (rcvPktAckNum - minExpectedAckNum + 1));
            }
            int pktSeqNumToAck = 0; // 后加的
            for (int i = 0; i <= rcvPktAckNum - minExpectedAckNum; i++){
                // updateStatistics(senderWindow.remove().getSeqnum());
                //------
                pktSeqNumToAck = senderWindow.remove().getSeqnum();
                updateCmmMap(pktSeqNumToAck);
                //------
                if (senderWindow.size() == 0)
                    stopTimer(A);

            }
            //------
            updateRttMap(pktSeqNumToAck);
            //-------
            refillSenderWindow();
        // if a duplicated ack is received
        } else {
            System.out.println("aInput: A got a duplicated ACK from B, " + rcvPkt);
            System.out.println("Resend the next unACK'ed packet: " + senderWindow.getFirst());
            sendPkt(senderWindow.getFirst());
            reTransCnt++;
        }

        System.out.println("aInput: End");
    }

    // This routine will be called when A's timer expires (thus generating a
    // timer interrupt). You'll probably want to use this routine to control
    // the retransmission of packets. See startTimer() and stopTimer(), above,
    // for how the timer is started and stopped.
    protected void aTimerInterrupt() {
        System.out.println("A Timer Interrupt at local time: " + getTime());
        if(senderWindow.size() > 0){
            sendPkt(senderWindow.getFirst());
            reTransCnt++;
        } else {
            stopTimer(A);
            startTimer(A, RxmtInterval);
        }

    }

    // This routine will be called once, before any of your other A-side
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity A).
    protected void aInit() {

    }

    // This routine will be called whenever a packet sent from the B-side
    // (i.e. as a result of a toLayer3() being done by an A-side procedure)
    // arrives at the B-side.  "packet" is the (possibly corrupted) packet
    // sent from the A-side.
    protected void bInput(Packet packet) {
        System.out.println("bInput: Start");
        System.out.println("bInput: B getting " + packet.getPayload());

        // Packet is corrupted
        if (packet.getChecksum() != computeCheckSum(packet)){
            System.out.println("bInput: B got a corrupted packet from A");
            corruptedPktCnt++;
            System.out.println("bInput: End");
            return;
        }

        // Packet is not corrupted
        int rcvdSeqNum = packet.getSeqnum();
        // packet is the expected one
        if (rcvdSeqNum == nextExpectSeqNum) {
            System.out.println("bInput: Expecting pkt" + nextExpectSeqNum + ", got pkt" + rcvdSeqNum);
            // deliver data
            lastRcvPacket = packet;
            toLayer5(lastRcvPacket.getPayload());
            // slide receiver window
            receiverWindow.remove();
            receiverWindow.add(null);
            // update variables
            nextExpectSeqNum++;
            ackedPktCnt++;
            deliveredPktCnt++;

            // check receiver window to deliver packets that are in order
            while (receiverWindow.getFirst() != null && receiverWindow.getFirst().getSeqnum() == nextExpectSeqNum){
                // deliver data
                lastRcvPacket = receiverWindow.getFirst();
                toLayer5(lastRcvPacket.getPayload());
                // slide receiver window
                receiverWindow.remove();
                receiverWindow.add(null);
                // update variables
                nextExpectSeqNum++;
                deliveredPktCnt++;
            }
            // set ackNum and recompute checkSum
            lastRcvPacket.setAcknum(lastRcvPacket.getSeqnum());
            lastRcvPacket.setPayload("");
            lastRcvPacket.setChecksum(computeCheckSum(lastRcvPacket));
            toLayer3(B, lastRcvPacket);
            // packet is not expected and is acknowledged
        } else if (rcvdSeqNum < nextExpectSeqNum){
            System.out.println("bInput: Expecting pkt" + nextExpectSeqNum + ", got pkt" + rcvdSeqNum);
            System.out.println("bInput: Sending Duplicate ACK:" + lastRcvPacket.getSeqnum());
            toLayer3(B, lastRcvPacket);
            ackedPktCnt++;
            // packet is not expected but with a seqNum can be put in receiver window, buffer it
        } else if (rcvdSeqNum > nextExpectSeqNum && rcvdSeqNum < nextExpectSeqNum + WindowSize) {
            System.out.println("bInput: Expecting pkt" + nextExpectSeqNum + ", got pkt" + rcvdSeqNum);

            // check if this out-of-order packet if previously buffered
            if (receiverWindow.get(rcvdSeqNum - nextExpectSeqNum) == null) {
                receiverWindow.set(rcvdSeqNum - nextExpectSeqNum, packet);
                System.out.println("bInput: Putting pkt" + rcvdSeqNum + " into receiver window["
                        + (rcvdSeqNum - nextExpectSeqNum) + "]");
            } else {
                System.out.println("bInput: Detecting duplicated pkt" + rcvdSeqNum + ", this packet is discarded");
            }

            // check if there is a last-received packet (case where there isn't: expecting pkt0, but not receiving it)
            if (lastRcvPacket != null) {
                System.out.println("bInput: Sending Duplicate ACK: " + lastRcvPacket.getSeqnum());
                toLayer3(B, lastRcvPacket);
                ackedPktCnt++;
            } else {
                System.out.println("bInput: Since pkt0 is expected, no previously acknowledged packet to resend, just wait..");
            }
        }
        System.out.println("bInput: End");
    }

    // This routine will be called once, before any of your other B-side
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity B).
    protected void bInit() {
        lastRcvPacket = null;
        for (int i = 0; i < WindowSize; i++) {
            receiverWindow.add(null);
        }
    }


    // Use to print final statistics
    protected void Simulation_done() {
        double lostPktRatio =(reTransCnt - corruptedPktCnt) / (double) (originalTransCnt + reTransCnt + ackedPktCnt);
        double corruptedPktRatio = (corruptedPktCnt) / (double) (originalTransCnt + ackedPktCnt + corruptedPktCnt);
        double avgRtt = rttSum / (double) rttCnt;
        double avgCmm = cmmSum / (double) cmmCnt;

        // TO PRINT THE STATISTICS, FILL IN THE DETAILS BY PUTTING VARIBALE NAMES. DO NOT CHANGE THE FORMAT OF PRINTED OUTPUT
        System.out.println("\n\n==================SR STATISTICS===================");
        System.out.println("Number of original packets transmitted by A:" + originalTransCnt);
        System.out.println("Number of retransmissions by A:" + reTransCnt);
        System.out.println("Number of data packets delivered to layer 5 at B:" + deliveredPktCnt);
        System.out.println("Number of ACK packets sent by B:" + ackedPktCnt);
        System.out.println("Number of corrupted packets:" + corruptedPktCnt);
        System.out.println("Ratio of lost packets:" + lostPktRatio );
        System.out.println("Ratio of corrupted packets:" + corruptedPktRatio);
        System.out.println("Average RTT:" + avgRtt);
        System.out.println("Average communication time:" + avgCmm);
        System.out.println("==================================================");

        // PRINT YOUR OWN STATISTIC HERE TO CHECK THE CORRECTNESS OF YOUR PROGRAM
        System.out.println("\nEXTRA: ");
        System.out.println("All RTT: " + rttSum);
        System.out.println("Counter RTT: " + rttCnt);
        System.out.println("Total time to communicate: " + cmmSum);
        System.out.println("Counter for time to communicate:" + cmmCnt);
    }

}