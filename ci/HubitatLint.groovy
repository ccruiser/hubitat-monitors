/*
 * HubitatLint.groovy - a local, executable approximation of the
 * Hubitat Groovy sandbox for apps/drivers.
 *
 * Why this exists: stock groovyc proves Groovy code COMPILES, but
 * Hubitat rejects otherwise-legal code at save time with its
 * sandbox (e.g. "Expression [MethodCallExpression] is not allowed:
 * java.lang.System.currentTimeMillis()" - Plumcrest Hub Updater
 * v0.2.0, Oct 23, 2026). That message format is the stock
 * SecureASTCustomizer denial, so this linter compiles each file
 * with a SecureASTCustomizer configured with the known Hubitat
 * restrictions and reports the same class of verdict locally,
 * before the file ever reaches the hub.
 *
 * It is an approximation, not the hub: Hubitat's exact whitelist
 * is theirs. A PASS here means "no known sandbox violation", and
 * the calibration set in tests/ pins the behavior: the poison file
 * must FAIL on every planted violation and the known-good repo
 * drivers must PASS.
 *
 * Usage: groovy HubitatLint.groovy <file.groovy> [more files...]
 * Exit 0 = all files pass, 1 = at least one rejection.
 */

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.customizers.SecureASTCustomizer
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression

// Classes whose static/receiver method calls Hubitat's sandbox
// rejects (observed on the hub or documented community knowledge).
def bannedReceivers = [
    "java.lang.System",
    "java.lang.Runtime",
    "java.lang.ClassLoader",
    "java.lang.Thread",
    "java.lang.ProcessBuilder",
    "groovy.lang.GroovyShell",
    "groovy.util.Eval",
    "java.net.URLEncoder",
    "java.net.URLDecoder",
    "java.net.Socket",
    "java.net.ServerSocket",
]

// Classes that may not be constructed at all in the sandbox.
def bannedConstructors = [
    "java.io.File",
    "java.io.FileInputStream",
    "java.io.FileOutputStream",
    "java.io.FileReader",
    "java.io.FileWriter",
    "java.lang.Thread",
    "java.lang.ProcessBuilder",
    "java.lang.ClassLoader",
    "groovy.lang.GroovyShell",
    "java.net.Socket",
    "java.net.ServerSocket",
] as Set

def customizer = new SecureASTCustomizer()
customizer.setReceiversBlackList(bannedReceivers)
customizer.setStarImportsBlacklist(["java.io"])

customizer.addExpressionCheckers({ expr ->
    if (expr instanceof ConstructorCallExpression) {
        def typeName = expr.type?.name
        if (typeName in bannedConstructors) {
            throw new SecurityException(
                "Expression [ConstructorCallExpression] is not allowed: new ${typeName}(...) at line ${expr.lineNumber}")
        }
    }
    if (expr instanceof PropertyExpression) {
        if (expr.propertyAsString == "class") {
            throw new SecurityException(
                "Expression [PropertyExpression] is not allowed: .class access at line ${expr.lineNumber}")
        }
    }
    if (expr instanceof MethodCallExpression) {
        if (expr.methodAsString == "getClass") {
            throw new SecurityException(
                "Expression [MethodCallExpression] is not allowed: getClass() at line ${expr.lineNumber}")
        }
    }
    return true
} as SecureASTCustomizer.ExpressionChecker)

def config = new CompilerConfiguration()
config.addCompilationCustomizers(customizer)
def outDir = new File("/tmp/hubitat-lint-out")
outDir.mkdirs()
config.setTargetDirectory(outDir)

int failures = 0
for (String path : args) {
    def loader = new GroovyClassLoader(this.class.classLoader, config)
    try {
        loader.parseClass(new File(path))
        println "PASS  ${path}"
    } catch (MultipleCompilationErrorsException e) {
        failures++
        println "FAIL  ${path}"
        e.errorCollector.errors.each { err ->
            def msg = err.cause?.message ?: err.message
            msg.readLines().findAll { it.trim() }.take(3).each { println "      ${it}" }
        }
    } catch (SecurityException e) {
        failures++
        println "FAIL  ${path}"
        println "      ${e.message}"
    } catch (Throwable e) {
        failures++
        println "FAIL  ${path}"
        println "      ${e.class.name}: ${e.message}"
    } finally {
        loader.close()
    }
}
System.exit(failures == 0 ? 0 : 1)
