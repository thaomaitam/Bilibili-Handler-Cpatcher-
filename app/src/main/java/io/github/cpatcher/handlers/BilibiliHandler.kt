package io.github.cpatcher.handlers

// Stage 1: Android/Java stdlib imports
import java.lang.reflect.Modifier

// Stage 2: Third-party imports (Xposed, DexKit)
import org.luckypray.dexkit.query.enums.StringMatchType

// Stage 3: Project imports
import io.github.cpatcher.arch.IHook
import io.github.cpatcher.arch.ObfsInfo
import io.github.cpatcher.arch.call
import io.github.cpatcher.arch.createObfsTable
import io.github.cpatcher.arch.hookAllBefore
import io.github.cpatcher.arch.hookAllConstant
import io.github.cpatcher.arch.hookReplace
import io.github.cpatcher.arch.toObfsInfo
import io.github.cpatcher.bridge.HookParam
import io.github.cpatcher.logE
import io.github.cpatcher.logI

class BilibiliHandler : IHook() {
    companion object {
        // Version constants FIRST
        private const val TABLE_VERSION = 1
        
        // System constants SECOND (none needed for this handler)
        
        // Logical keys THIRD
        private const val KEY_SPLASH_CLASS = "splash_model_class"
        private const val KEY_ISVALID_METHOD = "is_valid_method"
        private const val KEY_SPLASH_ACTIVITY = "splash_activity_class"
        
        // Package constants
        private const val PACKAGE_BILIBILI_INTL = "com.bstar.intl"
        private const val PATTERN_SPLASH_UI = "splash"
    }
    
    override fun onHook() {
        // Phase 1: Package validation
        if (loadPackageParam.packageName != PACKAGE_BILIBILI_INTL) {
            logI("${this::class.simpleName}: Skipping - not Bilibili International context")
            return
        }
        
        // Phase 2: Obfuscation-resilient fingerprinting
        val obfsTable = runCatching {
            createObfsTable("bilibili", TABLE_VERSION) { bridge ->
                // Find splash model class with multi-criteria fingerprinting
                val splashClassList = bridge.findClass {
                    matcher {
                        className = "com.bstar.intl.ui.splash.ad.model.Splash"
                    }
                }
                
                // Extract first ClassData from list
                val splashClass = splashClassList.firstOrNull()
                    ?: throw IllegalStateException("Splash model class fingerprint failed")
                
                // Find isValid method with comprehensive criteria
                val isValidMethodList = bridge.findMethod {
                    matcher {
                        declaredClass = splashClass.className
                        methodName = "isValid"
                        returnType = "boolean"
                        paramTypes = listOf()  // No parameters
                    }
                }
                
                // Extract first MethodData from list or use fallback
                val isValidMethod = isValidMethodList.firstOrNull() ?: run {
                    // Fallback: Find by method characteristics
                    val fallbackList = bridge.findMethod {
                        matcher {
                            declaredClass = splashClass.className
                            returnType = "boolean"
                            paramTypes = listOf()
                            modifiers = Modifier.PUBLIC
                        }
                    }
                    
                    // Filter and extract first matching method
                    fallbackList.filter { method ->
                        // Heuristic: validation methods check internal state
                        method.usingFields.isNotEmpty()
                    }.firstOrNull() ?: throw IllegalStateException("isValid method fingerprint failed")
                }
                
                // Optional: Find splash activity for additional suppression
                val splashActivityList = bridge.findClass {
                    matcher {
                        superClass = "android.app.Activity"
                        usingStrings {
                            add(PATTERN_SPLASH_UI, StringMatchType.Contains)
                        }
                    }
                }
                
                // Extract activity from list with filtering
                val splashActivity = splashActivityList
                    .filter { clazz -> clazz.className.contains("splash", ignoreCase = true) }
                    .firstOrNull()
                
                // Build obfuscation mapping table
                buildMap {
                    put(KEY_SPLASH_CLASS, splashClass.toObfsInfo())
                    put(KEY_ISVALID_METHOD, isValidMethod.toObfsInfo())
                    splashActivity?.let {
                        put(KEY_SPLASH_ACTIVITY, it.toObfsInfo())
                    }
                }
            }
        }.getOrElse { throwable ->
            logE("${this::class.simpleName}: Fingerprinting failed, attempting direct hook", throwable)
            performDirectHook()
            return
        }
        
        // Phase 3: Runtime hook application
        implementAdvertisementSuppression(obfsTable)
        
        // Phase 4: Success logging
        logI("${this::class.simpleName}: Successfully initialized ad suppression system")
    }
    
    private fun implementAdvertisementSuppression(obfsTable: Map<String, ObfsInfo>) {
        // Primary strategy: Hook isValid method
        runCatching {
            val methodInfo = obfsTable[KEY_ISVALID_METHOD]
                ?: throw IllegalStateException("Missing isValid method mapping")
            
            val targetClass = findClass(methodInfo.className)
            
            // Force isValid to return false
            targetClass.hookAllConstant(methodInfo.memberName, false)
            
            logI("${this::class.simpleName}: Primary ad validation bypass installed")
            
        }.onFailure { primary ->
            logE("${this::class.simpleName}: Primary strategy failed", primary)
            
            // Fallback strategy: Hook all boolean validation methods
            runCatching {
                val classInfo = obfsTable[KEY_SPLASH_CLASS]
                    ?: throw IllegalStateException("Missing Splash class mapping")
                
                val splashClass = findClass(classInfo.className)
                
                // Hook all potential validation methods
                splashClass.declaredMethods
                    .filter { method -> 
                        method.returnType == Boolean::class.java && method.parameterCount == 0 
                    }
                    .forEach { method ->
                        method.hookReplace { _ ->
                            false  // Invalidate all validations
                        }
                    }
                
                logI("${this::class.simpleName}: Fallback validation bypass installed")
                
            }.onFailure { fallback ->
                logE("${this::class.simpleName}: Fallback strategy failed", fallback)
            }
        }
        
        // Supplementary strategy: Skip splash activity
        obfsTable[KEY_SPLASH_ACTIVITY]?.let { activityInfo ->
            runCatching {
                val activityClass = findClass(activityInfo.className)
                
                // Immediately finish splash activity
                activityClass.hookAllBefore("onCreate") { param ->
                    param.thisObject?.call("finish")
                    param.result = null
                }
                
                logI("${this::class.simpleName}: Splash activity suppression installed")
                
            }.onFailure { activity ->
                logE("${this::class.simpleName}: Activity suppression failed", activity)
            }
        }
    }
    
    private fun performDirectHook() {
        // Direct hook without ObfsTable (less resilient)
        runCatching {
            val splashClass = findClassOrNull("com.bstar.intl.ui.splash.ad.model.Splash")
                ?: return@runCatching logE("${this::class.simpleName}: Splash class not found")
            
            // Hook multiple possible validation methods
            splashClass.hookAllConstant("isValid", false)
            splashClass.hookAllConstant("validate", false)
            splashClass.hookAllConstant("canShow", false)
            splashClass.hookAllConstant("shouldDisplay", false)
            
            logI("${this::class.simpleName}: Direct hook strategy successful")
            
        }.onFailure { direct ->
            logE("${this::class.simpleName}: Direct hook failed", direct)
            
            // Ultra-fallback: Pattern-based search
            performPatternBasedSearch()
        }
    }
    
    private fun performPatternBasedSearch() {
        // Last resort: Search by package patterns
        runCatching {
            val patterns = listOf(
                "com.bstar.intl.ui.splash",
                "com.bstar.intl.splash",
                "com.bilibili.intl.splash"
            )
            
            for (pattern in patterns) {
                findClassOrNull("$pattern.ad.model.Splash")?.let { splashClass ->
                    splashClass.declaredMethods
                        .filter { method ->
                            method.returnType == Boolean::class.java
                        }
                        .forEach { method ->
                            method.hookReplace { _ -> false }
                        }
                    
                    logI("${this::class.simpleName}: Pattern-based suppression installed for $pattern")
                    return@runCatching
                }
            }
            
            logE("${this::class.simpleName}: No splash classes found via pattern search")
            
        }.onFailure { pattern ->
            logE("${this::class.simpleName}: Pattern-based search failed", pattern)
        }
    }
}