package com.tj.linkmeter;

import java.io.IOException;

/** No Android in here. Seals bytes so that they cannot be read or changed without the key: {@link AesGcmSealer}. */
interface Sealer {
    /** The sealed form of {@code plain}; {@code aad} is bound to it (opening with other data fails). */
    byte[] seal(byte[] plain, byte[] aad) throws IOException;

    /** The bytes that were sealed; an IOException when they were changed, or sealed with another key or for another {@code aad}. */
    byte[] open(byte[] sealed, byte[] aad) throws IOException;
}
