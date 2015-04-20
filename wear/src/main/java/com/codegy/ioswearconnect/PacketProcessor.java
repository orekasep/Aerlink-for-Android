package com.codegy.ioswearconnect;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Created by kusabuka on 15/03/15.
 */
public class PacketProcessor {

    private enum PacketProcessingStatus {
        init,
        app_id,
        title,
        message,
        positiveAction,
        negativeAction,
        finish
    }

    private static final String TAG_LOG = "BLE_wear";

    private NotificationData notificationData;
    private ByteArrayOutputStream processingAttribute;

    private byte[] bytesFromPreviousPacket;
    // The number of bytes left to process on the current packet
    private int bytesLeftToProcess;
    // The number of bytes of the current attribute being processed that are in the next packet
    private int attributeBytesInNextPacket;

    private PacketProcessingStatus processingStatus;

    PacketProcessor() {
        processingAttribute = new ByteArrayOutputStream();
        init(null);
    }

    public void init(byte[] packet) {
        processingStatus = PacketProcessingStatus.init;

        bytesLeftToProcess = 0;
        attributeBytesInNextPacket = 0;
        bytesFromPreviousPacket = new byte[] {};

        processingAttribute.reset();

        if (packet != null) {
            notificationData = new NotificationData(packet);
        }
        else {
            notificationData = null;
        }
    }

    public NotificationData getNotificationData() {
        return notificationData;
    }

    public boolean hasFinishedProcessing() {
        return processingStatus == PacketProcessingStatus.finish && notificationData != null;
    }

    private int getAttributeLength(byte[] packet, int lengthIndex){
        //get att0's length
        byte[] byteLength = {packet[lengthIndex + 2], packet[lengthIndex + 1]};
        BigInteger length = new BigInteger(byteLength);
        return length.intValue();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        int aLen = a.length;
        int bLen = b.length;
        byte[] c = new byte[aLen+bLen];
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);
        return c;
    }

    private void updateProcessingStatus() {
        switch (processingStatus) {
            case init:
                Log.d(TAG_LOG, "$$$ init -> app_id.");
                processingStatus = PacketProcessingStatus.title;
                break;
            case app_id:
                Log.d(TAG_LOG, "$$$ finish app id reading.");
                processingStatus = PacketProcessingStatus.title;
                try {
                    notificationData.setAppId(new String(processingAttribute.toByteArray(), "UTF-8"));
                    processingAttribute.reset();
                    Log.d(TAG_LOG, "$$$ app_id : " + notificationData.getAppId());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                break;
            case title:
                Log.d(TAG_LOG, "$$$ finish title  reading.");
                processingStatus = PacketProcessingStatus.message;
                try {
                    notificationData.setTitle(new String(processingAttribute.toByteArray(), "UTF-8"));
                    processingAttribute.reset();
                    Log.d(TAG_LOG, "$$$ title : " + notificationData.getTitle());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                break;
            case message:
                Log.d(TAG_LOG, "$$ finish message  reading.");
                if (notificationData.hasPositiveAction()) {
                    processingStatus = PacketProcessingStatus.positiveAction;
                }
                else if (notificationData.hasNegativeAction()) {
                    processingStatus = PacketProcessingStatus.negativeAction;
                }
                else {
                    processingStatus = PacketProcessingStatus.finish;
                }

                try {
                    notificationData.setMessage(new String(processingAttribute.toByteArray(), "UTF-8"));
                    processingAttribute.reset();
                    Log.d(TAG_LOG, "$$ message : " + notificationData.getMessage());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                break;
            case positiveAction:
                Log.d(TAG_LOG, "$$ finish positiveAction  reading.");

                if (notificationData.hasNegativeAction()) {
                    processingStatus = PacketProcessingStatus.negativeAction;
                }
                else {
                    processingStatus = PacketProcessingStatus.finish;
                }

                try {
                    notificationData.setPositiveAction(new String(processingAttribute.toByteArray(), "UTF-8"));
                    processingAttribute.reset();
                    Log.d(TAG_LOG, "$$ positiveAction : " + notificationData.getPositiveAction());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                break;
            case negativeAction:
                Log.d(TAG_LOG, "$$ finish negativeAction  reading.");
                processingStatus = PacketProcessingStatus.finish;
                try {
                    notificationData.setNegativeAction(new String(processingAttribute.toByteArray(), "UTF-8"));
                    processingAttribute.reset();
                    Log.d(TAG_LOG, "$$ negativeAction : " + notificationData.getNegativeAction());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                break;
            default:
                Log.d(TAG_LOG, "$$.");
                break;
        }
    }

    public void process(byte[] packet) {
        // Get size of received data
        packet = concat(bytesFromPreviousPacket, packet);
        bytesLeftToProcess = packet.length;
        bytesFromPreviousPacket = new byte[] {};

        int attributeIndex;

        while (bytesLeftToProcess > 0) {
            if (attributeBytesInNextPacket > 0) {
                // Still processing attribute started in a previous packet

                if (bytesLeftToProcess < attributeBytesInNextPacket) {
                    // The attribute is still not finished with this packet

                    // Save attribute data
                    processingAttribute.write(packet, 0, bytesLeftToProcess);

                    // Update bytes left of current attribute
                    attributeBytesInNextPacket -= bytesLeftToProcess;

                    // All bytes have been processed
                    bytesLeftToProcess = 0;
                }
                else {
                    // The attribute ends in this packet

                    // Save attribute data
                    processingAttribute.write(packet, 0, attributeBytesInNextPacket);

                    // There may be bytes of another attribute left in this packet
                    bytesLeftToProcess -= attributeBytesInNextPacket;

                    if (bytesLeftToProcess > 0 && bytesLeftToProcess <= 2) {
                        // Not enough bytes to start processing next attribute

                        // Save bytes for next packet
                        bytesFromPreviousPacket = Arrays.copyOfRange(packet, attributeBytesInNextPacket, packet.length);
                        bytesLeftToProcess = 0;
                    }

                    // This attribute's bytes have been processed
                    attributeBytesInNextPacket = 0;

                    updateProcessingStatus();
                }
            }
            else if (bytesLeftToProcess > 0) {
                // Attribute index
                if (processingStatus == PacketProcessingStatus.init) {
                    // Previous bytes' data is already known
                    attributeIndex = 5;
                }
                else {
                    attributeIndex = packet.length - bytesLeftToProcess;
                }

                // Length of attribute to read
                int attributeLength = getAttributeLength(packet, attributeIndex);

                // Not counting bytes offering attribute length info
                int bytesInCurrentPacket = packet.length - (attributeIndex + 3);

                if (bytesInCurrentPacket < attributeLength) {
                    // The attribute is divided

                    // Save attribute data
                    processingAttribute.write(packet, attributeIndex + 3, bytesInCurrentPacket);

                    // Update bytes left of current attribute
                    attributeBytesInNextPacket = attributeLength - bytesInCurrentPacket;

                    if (processingStatus == PacketProcessingStatus.init) {
                        processingStatus = PacketProcessingStatus.app_id;
                    }

                    // All bytes have been processed
                    bytesLeftToProcess = 0;
                }
                else {
                    // The attribute ends in this packet

                    // Save attribute data
                    processingAttribute.write(packet, attributeIndex + 3, attributeLength);

                    // This attribute's bytes have been processed
                    attributeBytesInNextPacket = 0;

                    // There may be bytes of another attribute left in this packet
                    bytesLeftToProcess = bytesInCurrentPacket - attributeLength;

                    if (bytesLeftToProcess > 0 && bytesLeftToProcess <= 2) {
                        // Not enough bytes to start processing next attribute

                        // Offset of processed bytes
                        int offset = attributeIndex + 3 + attributeLength;

                        // Save bytes for next packet
                        bytesFromPreviousPacket = Arrays.copyOfRange(packet, offset, packet.length);
                        bytesLeftToProcess = 0;
                    }

                    updateProcessingStatus();
                }
            }
        }
    }
}