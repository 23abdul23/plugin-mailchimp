package io.kestra.plugin.mailchimp.models;

/** Status a member can be given; Mailchimp expects it in lower case. */
public enum MemberStatus {
    SUBSCRIBED,
    UNSUBSCRIBED,
    CLEANED,
    PENDING,
    TRANSACTIONAL
}
