package com.selfanalyst.mail;

import java.time.Duration;

/** Only the two explicit, pre-approved operations can enter the built-in write transport. */
interface MailWriteTransport {
    void write(MailWriteService.Prepared prepared, char[] authorizationCode, MailWriteService.WriteGate gate,
               Duration timeout);
}
