package io.local.tvopds;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.IOException;

/** Validates a persisted read-only SAF grant and a currently mounted USB tree. */
final class UsbLibraryAccess {
    private UsbLibraryAccess() {}

    static String verify(Context context, Uri tree) throws IOException {
        boolean hasGrant = false;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri()) && permission.isReadPermission()) {
                hasGrant = true;
                break;
            }
        }
        if (!hasGrant) throw new IOException("Folder permission was lost. Select the USB folder again.");
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            String displayName;
            try (Cursor c = context.getContentResolver().query(root,
                    new String[]{Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (c == null || !c.moveToFirst())
                    throw new IOException("USB folder is unavailable. Reconnect the drive.");
                if (!Document.MIME_TYPE_DIR.equals(c.getString(1)))
                    throw new IOException("Selected location is not a directory.");
                displayName = c.getString(0);
            }
            // Some TV firmware keeps a stale root in the picker even after USB removal.
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            try (Cursor c = context.getContentResolver().query(children,
                    new String[]{Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (c == null) throw new IOException("Cannot list USB folder. Reconnect the drive.");
            }
            return displayName == null ? rootId : displayName;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("USB drive is not accessible. Reconnect it or reselect the folder.", e);
        }
    }
}
