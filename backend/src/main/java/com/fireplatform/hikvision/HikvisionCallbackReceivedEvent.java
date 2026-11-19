package com.fireplatform.hikvision;

/** Signals that a newly persisted callback receipt committed and is ready for immediate decoding. */
public record HikvisionCallbackReceivedEvent(String receiptId) { }
