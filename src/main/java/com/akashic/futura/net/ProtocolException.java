package com.akashic.futura.net;

import java.io.IOException;

/** A message that breaks the protocol: malformed, over a limit, or from the wrong side. */
public final class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
