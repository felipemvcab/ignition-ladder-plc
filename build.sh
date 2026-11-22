#!/usr/bin/env zsh
set -euo pipefail

PROJECT_DIR="/Users/felipe/.gemini/antigravity/scratch/ignition-ladder-plc"
JAVA_HOME="/opt/homebrew/opt/openjdk@17"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
JAVA="$JAVA_HOME/bin/java"

IGNITION_LIB="/usr/local/ignition/lib"
CP="$IGNITION_LIB/core/gateway/*:$IGNITION_LIB/core/common/*:$IGNITION_LIB/*"
SDK81_DIR="$PROJECT_DIR/sdk-81"

BUILD_DIR="$PROJECT_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
CLASSES81_DIR="$BUILD_DIR/classes-81"
SRC81_DIR="$BUILD_DIR/src-81/com/antigravity/ladder/gateway"
DIST_DIR="$BUILD_DIR/dist"

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR" "$CLASSES81_DIR" "$SRC81_DIR" "$DIST_DIR"

echo "Compiling Java sources against Ignition 8.3.10 SDK (Gateway Scope)..."
"$JAVAC" -cp "$CP" -d "$CLASSES_DIR" \
  "$PROJECT_DIR"/src/main/java/com/antigravity/ladder/engine/*.java \
  "$PROJECT_DIR"/src/main/java/com/antigravity/ladder/gateway/*.java \
  "$PROJECT_DIR"/src/main/java/com/antigravity/ladder/build/*.java

# Copy web resources into classes directory so they are bundled inside the JAR
cp -R "$PROJECT_DIR/src/main/resources/web" "$CLASSES_DIR/web"

JAR_FILE="$DIST_DIR/ladder-plc-gateway-1.0.0.jar"
MODL_FILE="$DIST_DIR/LadderLogic-SoftPLC-1.0.0.modl"

"$JAR" --create --file "$JAR_FILE" -C "$CLASSES_DIR" com -C "$CLASSES_DIR" web

echo "Running Verification Tests & Packaging Ignition 8.3 .modl..."
"$JAVA" -cp "$CLASSES_DIR:$CP" com.antigravity.ladder.build.ModulePackagerAndTest "$JAR_FILE" "$MODL_FILE" "8.3.0"

# =====================================================================
# IGNITION 8.1 COMPATIBLE BUILD (Java 11 bytecode + javax.servlet)
# =====================================================================
if [ -d "$SDK81_DIR" ] && [ -f "$SDK81_DIR/gateway-api-8.1.50.jar" ]; then
  echo ""
  echo "Compiling Java 11 bytecode against Ignition 8.1 SDK (javax.servlet)..."

  # Generate 8.1-compatible LadderGatewayHook.java (removes 8.3-only SystemJsModule nav + uses 8.1 ProviderConfiguration)
  python3 -c "
import re
src = open('$PROJECT_DIR/src/main/java/com/antigravity/ladder/gateway/LadderGatewayHook.java').read()
src = src.replace('import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;\n', '')
src = src.replace('import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProviderConfiguration;', 'import com.inductiveautomation.ignition.gateway.tags.managed.ProviderConfiguration;')
src = re.sub(r'\n\s*try \{\s*SystemJsModule jsModule[\s\S]*?logger\.error\(\"Failed registering Gateway navigation[^\n]+\n\s*\}', '\n        // Ignition 8.1 mounts the Web IDE servlet directly at /system/ladder', src)
src = re.sub(r'ManagedTagProviderConfiguration providerConfig = ManagedTagProviderConfiguration[\s\S]*?\.build\(\);', '''ProviderConfiguration providerConfig = new ProviderConfiguration(TagImageManager.MANAGED_PROVIDER_NAME)
                    .setAllowTagDeletion(true)
                    .setAllowTagCustomization(true)
                    .setPersistTags(false)
                    .setAllowDefaultWriteHandling(true)
                    .setHasDataTypes(true);''', src)
open('$SRC81_DIR/LadderGatewayHook.java', 'w').write(src)

# Generate 8.1-compatible LadderStudioServlet.java (javax.servlet.* + 8.1 BasicAuthChallenge)
srv = open('$PROJECT_DIR/src/main/java/com/antigravity/ladder/gateway/LadderStudioServlet.java').read()
srv = srv.replace('import jakarta.servlet.', 'import javax.servlet.')
srv = srv.replace('new BasicAuthChallenge(username, password).toSimpleAuthChallenge()', 'new BasicAuthChallenge(username, password)')
open('$SRC81_DIR/LadderStudioServlet.java', 'w').write(srv)
"

  "$JAVAC" --release 11 -cp "$SDK81_DIR/*" -d "$CLASSES81_DIR" \
    "$PROJECT_DIR"/src/main/java/com/antigravity/ladder/engine/*.java \
    "$SRC81_DIR/LadderGatewayHook.java" \
    "$SRC81_DIR/LadderStudioServlet.java"

  cp -R "$PROJECT_DIR/src/main/resources/web" "$CLASSES81_DIR/web"

  JAR81_FILE="$DIST_DIR/ladder-plc-gateway-1.0.0-ign81.jar"
  MODL81_FILE="$DIST_DIR/LadderLogic-SoftPLC-1.0.0-Ignition8.1.modl"

  "$JAR" --create --file "$JAR81_FILE" -C "$CLASSES81_DIR" com -C "$CLASSES81_DIR" web

  echo "Packaging & Signing Ignition 8.1 .modl..."
  "$JAVA" -cp "$CLASSES_DIR:$CP" com.antigravity.ladder.build.ModulePackagerAndTest "$JAR81_FILE" "$MODL81_FILE" "8.1.0" "skipTests"
fi
