// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class ClientChatEvent extends Event implements ICancellableEvent {
    private final String original;
    private String message;
    public ClientChatEvent(String message) { this.original = message; this.message = message; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getOriginalMessage() { return original; }
}
