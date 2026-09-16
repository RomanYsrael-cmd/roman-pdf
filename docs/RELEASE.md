# Release packaging and signing

ROMAN PDF keeps release signing optional in version control. `assembleRelease` and `bundleRelease` remain runnable on a clean checkout and produce an unsigned artifact when no signing inputs are present. When all four inputs are available, the same tasks produce signed output.

## Local signing properties

Create an ignored `signing.properties` file at the project root:

```properties
storeFile=work/roman-pdf-release.keystore
storePassword=<local secret>
keyAlias=roman-pdf
keyPassword=<local secret>
```

`storeFile` may be absolute or relative to the project root. Keep the keystore and this file outside commits. The repository ignores `signing.properties`, `*.keystore`, and `*.jks`.

## Environment-only signing

For CI or a shell that should not create a properties file, provide:

```text
ROMAN_PDF_STORE_FILE
ROMAN_PDF_STORE_PASSWORD
ROMAN_PDF_KEY_ALIAS
ROMAN_PDF_KEY_PASSWORD
```

The environment values are used when the matching properties-file value is absent. Do not echo them in build logs.

## Build commands

```text
gradlew.bat assembleRelease
gradlew.bat bundleRelease
```

The release application ID is `com.romanysrael.romanpdf`, version `0.5.0` / version code `5`, with R8 and resource shrinking enabled. Debug uses the `.debug` suffix and remains independently installable.

Before distribution, verify the signed artifact with the Android build-tools `apksigner verify --verbose` command and install it on a test device. A locally generated validation key is suitable only for local QA; production distribution should use the organization’s protected keystore and CI secret store.
