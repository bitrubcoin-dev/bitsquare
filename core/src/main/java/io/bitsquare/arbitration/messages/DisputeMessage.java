/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.arbitration.messages;

import io.bitsquare.app.Version;
import io.bitsquare.common.util.UID;
import io.bitsquare.p2p.messaging.MailboxMessage;

public abstract class DisputeMessage implements MailboxMessage {
    private final int messageVersion = Version.getP2PMessageVersion();
    private final String uid = UID.getUUID();

    @Override
    public int getMessageVersion() {
        return messageVersion;
    }

    @Override
    public String getUID() {
        return uid;
    }
}
