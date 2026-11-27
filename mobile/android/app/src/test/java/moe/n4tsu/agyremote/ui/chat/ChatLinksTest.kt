// SPDX-License-Identifier: AGPL-3.0-only
package moe.n4tsu.agyremote.ui.chat

import moe.n4tsu.agyremote.ui.chat.ChatLinks.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLinksTest {
    @Test fun webLinksNeedAHost() {
        assertEquals(Kind.WEB, ChatLinks.classify("https://example.com/docs?a=1#x"))
        assertEquals(Kind.WEB, ChatLinks.classify("HTTP://Example.com"))
        assertEquals(Kind.REFUSED, ChatLinks.classify("https://"))
        assertEquals(Kind.REFUSED, ChatLinks.classify("https:relative"))
    }

    @Test fun artifactLinksOpenInTheApp() {
        // The shape agy writes for files and artifacts; these crashed when handed to ACTION_VIEW.
        assertEquals(Kind.FILE, ChatLinks.classify("file:///Volumes/500/agy-remote-testbed/index.html#L169-L171"))
        assertEquals(Kind.FILE, ChatLinks.classify("file:///Users/me/.gemini/antigravity-cli/brain/abc/walkthrough.md"))
        assertEquals(Kind.FILE, ChatLinks.classify("references/sdk.md"))
        assertEquals(Kind.FILE, ChatLinks.classify("/abs/path with space.txt"))
    }

    @Test fun everythingElseIsRefused() {
        for (l in listOf("agy-rem://pair?id=x&ps=y", "intent://scan/#Intent;scheme=zxing;end", "tel:123", "sms:1", "javascript:alert(1)",
                         "content://com.example/x", "market://details?id=x", "mailto:a@b.c", "data:text/html,hi", "")) {
            assertEquals(l, Kind.REFUSED, ChatLinks.classify(l))
        }
    }
}
