package com.codegy.aerlink.connection;

import java.util.UUID;

/**
 * Created by Guiye on 19/5/15.
 */
public class CharacteristicIdentifier {

    private UUID serviceUUID;
    private String characteristicUUID;

    public CharacteristicIdentifier(UUID serviceUUID, String characteristicUUID) {
        this.serviceUUID = serviceUUID;
        this.characteristicUUID = characteristicUUID;
    }

    public UUID getServiceUUID() {
        return serviceUUID;
    }

    public String getCharacteristicUUID() {
        return characteristicUUID;
    }

}
