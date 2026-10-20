# Kestra Mailchimp Plugin

## What

- Provides plugin components under `io.kestra.plugin.mailchimp` to work with Mailchimp from Kestra flows.
- Tasks: `account.Ping`, `audiences.{ListAudiences,ListMembers,UpsertMember,BatchSubscribe,UpdateMemberTags}`, `campaigns.{ListCampaigns,SendCampaign}`, `reports.{GetCampaignReport,ListEmailActivity}`.
- Triggers: `audiences.NewSubscriberTrigger`, `audiences.MemberStatusChangeTrigger`, `campaigns.CampaignSentTrigger`.
- Details: `src/main/resources/doc/io.kestra.plugin.mailchimp.md`.

## Why

- What user problem does this solve? Teams that manage audiences and campaigns in Mailchimp otherwise call its REST API from shell or Python tasks.
- Why would a team adopt this plugin in a workflow? It keeps Mailchimp steps in the same Kestra flow as upstream data preparation, retries and notifications.
- What operational/business outcome does it enable? Audience syncs and campaign sends run from a single flow, without custom API glue code.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.mailchimp`:

- root: `AbstractMailchimpTask`, `AbstractMailchimpFetchTask` (list tasks), `AbstractMailchimpTrigger`, `MailchimpClient` (HTTP, auth, retry), `MailchimpConnectionInterface`, `MailchimpException`
- `account`, `audiences`, `campaigns`, `reports`: tasks and triggers (one `package-info.java` each)
- `models`: `FetchOutput`, `Fetcher` (paged reads), `Rows` (query and row conversion), `MemberStatus`, `SortDir`, `SubscriberHash`
- `polling`: `CursorPoll`, `TriggerCursor` (trigger position in the KV store)

Uses Kestra's core `io.kestra.core.http.client.HttpClient` (no SDK). It replays a request once after a 429/503 and that cannot be configured, so POSTs carry a one-shot stream body, which Apache does not replay (see `MailchimpClient#request`).

### Project Structure

```
plugin-mailchimp/
├── src/main/java/io/kestra/plugin/mailchimp/{account,audiences,campaigns,reports,models}
├── src/main/resources/{doc,metadata,icons}
├── src/test/java/io/kestra/plugin/mailchimp/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Every task input is a `Property<T>` rendered with `runContext.render`; secrets use `@PluginProperty(secret = true)` and `@ToString.Exclude`.
- No live credentials in tests or in the repo.
- Build with JDK 21 to 23 (Lombok does not support newer JDKs yet). Run `./gradlew build` before pushing: it also lints the plugin docs.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://mailchimp.com/developer/marketing/api/
