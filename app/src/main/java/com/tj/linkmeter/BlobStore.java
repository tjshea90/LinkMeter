package com.tj.linkmeter;

import java.io.IOException;
import java.util.List;

/**
 * No Android in here. Named blobs of bytes: where the tablet keeps its saved copy of the texts and its own choices about them
 * (pinned, archived, drafts...). The real one is a folder of files ({@link FileBlobStore}); a sealed one encrypts what it stores;
 * the tests use one in memory.
 */
interface BlobStore {
    /** The bytes of a blob, or null when there is no blob of that name. */
    byte[] read(String name) throws IOException;

    void write(String name, byte[] data) throws IOException;

    /** Deletes a blob; a blob that is not there is not a failure. */
    void delete(String name) throws IOException;

    /** The names of all blobs. */
    List<String> names() throws IOException;
}
