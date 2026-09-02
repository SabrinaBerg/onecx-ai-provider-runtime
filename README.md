# onecx-ai-provider-runtime
OneCX AI Provider Runtime Service

## Contract Artifact

The build publishes a **versioned, immutable runtime contract artifact** in addition to the
primary JAR. It is the OpenAPI contract for the internal runtime service
(`src/main/openapi/openapi-runtime.yaml`), attached to the release with the classifier
`openapi-runtime`:

```
org.tkit.onecx:onecx-ai-provider-runtime:<version>:openapi-runtime@yaml
```

- It is attached during the Maven `package` phase (and therefore included in `deploy`), so it
  is published together with the primary artifact under the same immutable version.
- Consumers can resolve it by Maven coordinates without fetching source from a branch —
  it is a released artifact, not a branch file.
- Compatibility is covered by tests (`RuntimeRestControllerTest`,
  `RuntimeChatServiceReflectionTest`, `OpenApiContractPublicationTest`) that pin the text
  dispatch and provider-health request/response shapes.

See the Antora documentation (`docs/modules/onecx-ai-provider-runtime/pages/onecx-ai-provider-runtime-docs.adoc`,
**Runtime Contract Artifact** section) for consumption and compatibility details.
