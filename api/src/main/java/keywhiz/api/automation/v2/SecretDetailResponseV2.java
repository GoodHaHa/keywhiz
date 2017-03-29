package keywhiz.api.automation.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import keywhiz.api.model.SanitizedSecret;
import keywhiz.api.model.SecretSeriesAndContent;

import static com.google.common.base.Strings.nullToEmpty;

@AutoValue public abstract class SecretDetailResponseV2 {
  SecretDetailResponseV2() {} // prevent sub-classing

  public static Builder builder() {
    return new AutoValue_SecretDetailResponseV2.Builder()
        .checksum("")
        .description("")
        .type(null)
        .expiry(0)
        .metadata(ImmutableMap.of());
  }

  @AutoValue.Builder public abstract static class Builder {
    public abstract Builder metadata(ImmutableMap<String, String> metadata);
    abstract SecretDetailResponseV2 autoBuild();

    public abstract Builder name(String name);
    public abstract Builder version(@Nullable Long version); // Unique ID in secrets_content table
    public abstract Builder checksum(String checksum);
    public abstract Builder description(String description);
    public abstract Builder createdAtSeconds(long createdAt);
    public abstract Builder createdBy(String person);
    public abstract Builder updatedAtSeconds(long updatedAt);
    public abstract Builder updatedBy(String person);
    public abstract Builder type(@Nullable String type);
    public abstract Builder expiry(long expiry);

    public Builder metadata(Map<String, String> metadata) {
      return metadata(ImmutableMap.copyOf(metadata));
    }

    public Builder seriesAndContent(SecretSeriesAndContent seriesAndContent) {
      return this
          .name(seriesAndContent.series().name())
          .version(seriesAndContent.series().currentVersion().orElse(null))
          .checksum(seriesAndContent.content().hmac())
          .description(seriesAndContent.series().description())
          .metadata(seriesAndContent.content().metadata())
          .createdAtSeconds(seriesAndContent.series().createdAt().toEpochSecond())
          .createdBy(seriesAndContent.series().createdBy())
          .updatedAtSeconds(seriesAndContent.series().updatedAt().toEpochSecond())
          .updatedBy(seriesAndContent.series().updatedBy())
          .expiry(seriesAndContent.content().expiry())
          .type(seriesAndContent.series().type().orElse(null));
    }

    public Builder sanitizedSecret(SanitizedSecret sanitizedSecret) {
      return this
          .name(sanitizedSecret.name())
          .description(sanitizedSecret.description())
          .checksum(sanitizedSecret.checksum())
          .createdAtSeconds(sanitizedSecret.createdAt().toEpochSecond())
          .createdBy(sanitizedSecret.createdBy())
          .updatedAtSeconds(sanitizedSecret.updatedAt().toEpochSecond())
          .updatedBy(sanitizedSecret.updatedBy())
          .type(sanitizedSecret.type().orElse(null))
          .expiry(sanitizedSecret.expiry())
          .metadata(sanitizedSecret.metadata())
          .version(sanitizedSecret.version().orElse(null));
    }

    public SecretDetailResponseV2 build() {
      return this.autoBuild();
    }
  }

  /**
   * Static factory method used by Jackson for deserialization
   */
  @SuppressWarnings("unused")
  @JsonCreator public static SecretDetailResponseV2 fromParts(
      @JsonProperty("name") String name,
      @JsonProperty("version") @Nullable Long version,
      @JsonProperty("description") @Nullable String description,
      @JsonProperty("checksum") String checksum,
      @JsonProperty("createdAtSeconds") long createdAtSeconds,
      @JsonProperty("createdBy") String createdBy,
      @JsonProperty("updatedAtSeconds") long updatedAtSeconds,
      @JsonProperty("updatedBy") String updatedBy,
      @JsonProperty("type") @Nullable String type,
      @JsonProperty("metadata") @Nullable Map<String, String> metadata,
      @JsonProperty("expiry") long expiry) {
    return builder()
        .name(name)
        .version(version)
        .description(nullToEmpty(description))
        .checksum(checksum)
        .createdAtSeconds(createdAtSeconds)
        .createdBy(createdBy)
        .updatedAtSeconds(updatedAtSeconds)
        .updatedBy(updatedBy)
        .type(type)
        .metadata(metadata == null ? ImmutableMap.of() : ImmutableMap.copyOf(metadata))
        .expiry(expiry)
        .build();
  }

  // TODO: Consider Optional values in place of Nullable.
  @JsonProperty("name") public abstract String name();
  @JsonProperty("version") @Nullable public abstract Long version();
  @JsonProperty("description") public abstract String description();
  @JsonProperty("checksum") public abstract String checksum();
  @JsonProperty("createdAtSeconds") public abstract long createdAtSeconds();
  @JsonProperty("createdBy") public abstract String createdBy();
  @JsonProperty("updatedAtSeconds") public abstract long updatedAtSeconds();
  @JsonProperty("updatedBy") public abstract String updatedBy();
  @JsonProperty("type") @Nullable public abstract String type();
  @JsonProperty("metadata") public abstract ImmutableMap<String, String> metadata();
  @JsonProperty("expiry") public abstract long expiry();

  @Override public final String toString() {
    return MoreObjects.toStringHelper(this)
        .add("name", name())
        .add("description", description())
        .add("checksum", checksum())
        .add("createdAtSeconds", createdAtSeconds())
        .add("createdBy", createdBy())
        .add("updatedAtSeconds", updatedAtSeconds())
        .add("updatedBy", updatedBy())
        .add("type", type())
        .add("metadata", metadata())
        .add("expiry", expiry())
        .omitNullValues()
        .toString();
  }
}

