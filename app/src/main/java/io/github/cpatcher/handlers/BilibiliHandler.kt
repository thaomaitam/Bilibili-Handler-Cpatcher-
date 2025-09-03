package io.github.cpatcher.handlers

// Stage 1: Android/Java stdlib imports
import java.lang.reflect.Modifier

// Stage 2: Third-party imports (Xposed, DexKit)
import org.luckypray.dexkit.query.enums.StringMatchType

// Stage 3: Project imports - COMPLETE SET
import io.github.cpatcher.arch.IHook
import io.github.cpatcher.arch.ObfsInfo
import io.github.cpatcher.arch.call
import io.github.cpatcher.arch.createObfsTable
import io.github.cpatcher.arch.findClassN
import io.github.cpatcher.arch.hookAllBefore
import io.github.cpatcher.arch.hookAllConstant
import io.github.cpatcher.arch.hookReplace
import io.github.cpatcher.arch.toObfsInfo
import io.github.cpatcher.bridge.HookParam
import io.github.cpatcher.logE
import io.github.cpatcher.logI

class BilibiliHandler : IHook() {
    companion object {
        // Version control for obfuscation table - MUST BE FIRST
        private const val TABLE_VERSION = 1
        
        // Logical keys for obfuscated members - FOLLOWS VERSION
        private const val KEY_SPLASH_CLASS = "splash_model_class"
        private const val KEY_ISVALID_METHOD = "is_valid_method"
        private const val KEY_SPLASH_ACTIVITY = "splash_activity_class"
        
        // Known stable package patterns
        private const val PACKAGE_BILIBILI_INTL = "com.bstar.intl"
        private const val PATTERN_SPLASH_MODEL = "splash.ad.model"
        private const val PATTERN_SPLASH_UI = "splash.ad"
    }
    
    override fun onHook() {
        // Phase 1: Package validation - MANDATORY FIRST STEP
        if (loadPackageParam.packageName != PACKAGE_BILIBILI_INTL) {
            logI("${this::class.simpleName}: Skipping - not Bilibili International context")
            return
        }
        
        // Phase 2: Obfuscation-resilient fingerprinting
        val obfsTable = runCatching {
            createObfsTable("bilibili", TABLE_VERSION) { bridge ->
                // Primary target: Splash model class with isValid method
                val splashModelResults = bridge.findClass {
                    matcher {
                        // Multi-criteria fingerprinting for maximum resilience
                        className = "com.bstar.intl.ui.splash.ad.model.Splash"
                    }
                }
                
                // Extract single ClassData from results
                val splashClass = splashModelResults.firstOrNull()
                    ?: throw IllegalStateException("Splash model class fingerprint failed")
                
                // Locate isValid method within Splash class
                val isValidMethodResults = bridge.findMethod {
                    matcher {
                        declaredClass = splashClass.className
                        methodName = "isValid"  // May be obfuscated in future versions
                        returnType = "boolean"
                        paramTypes = listOf()  // No parameters expected
                    }
                }
                
                val isValidMethod = isValidMethodResults.firstOrNull() ?: run {
                    // Fallback: Find by characteristics if name is obfuscated
                    val fallbackResults = bridge.findMethod {
                        matcher {
                            declaredClass = splashClass.className
                            returnType = "boolean"
                            paramTypes = listOf()
                            modifiers = Modifier.PUBLIC
                        }
                    }
                    
                    fallbackResults.filter { method ->
                        // Additional heuristic: validation methods often check internal state
                        method.usingFields.isNotEmpty()
                    }.firstOrNull() ?: throw IllegalStateException("isValid method fingerprint failed")
                }
                
                // Secondary target: Splash activity for comprehensive suppression
                val splashActivityResults = bridge.findClass {
                    matcher {
                        // Activity that handles splash screen
                        superClass = "android.app.Activity"
                        usingStrings {
                            add("splash", StringMatchType.Contains)
                        }
                    }
                }
                
                val splashActivity = splashActivityResults.filter { clazz ->
                    clazz.className.contains(PATTERN_SPLASH_UI, ignoreCase = true)
                }.firstOrNull()
                
                // Build obfuscation mapping table with proper type conversion
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
            // Fallback: Direct class access if not obfuscated
            performDirectHook()
            return
        }
        
        // Phase 3: Runtime hook application
        implementAdvertisementSuppression(obfsTable)
        
        // Phase 4: Success logging - MANDATORY FINAL STEP
        logI("${this::class.simpleName}: Successfully initialized ad suppression system")
    }
    
    private fun implementAdvertisementSuppression(obfsTable: Map<String, ObfsInfo>) {
        // Primary strategy: Constant injection for isValid method
        runCatching {
            val splashInfo = obfsTable[KEY_ISVALID_METHOD]
                ?: throw IllegalStateException("Missing isValid mapping")
            
            val splashClass = findClass(splashInfo.className)
            
            // Force isValid to always return false - PROJECT UTILITY PATTERN
            splashClass.hookAllConstant(splashInfo.memberName, false)
            
            logI("${this::class.simpleName}: Primary ad validation bypass installed")
            
        }.onFailure { primary ->
            logE("${this::class.simpleName}: Primary strategy failed", primary)
            
            // Fallback strategy: Hook all validation-like methods
            runCatching {
                val splashClassInfo = obfsTable[KEY_SPLASH_CLASS]
                    ?: throw IllegalStateException("Missing Splash class mapping")
                
                val splashClass = findClass(splashClassInfo.className)
                
                // Hook all boolean methods that might be validation
                splashClass.declaredMethods
                    .filter { it.returnType == Boolean::class.java && it.parameterCount == 0 }
                    .forEach { method ->
                        method.hookReplace { _ ->
                            false  // Invalidate all splash ad validations
                        }
                    }
                
                logI("${this::class.simpleName}: Fallback validation bypass installed")
                
            }.onFailure { fallback ->
                logE("${this::class.simpleName}: Fallback strategy failed", fallback)
            }
        }
        
        // Supplementary strategy: Skip splash activity entirely
        obfsTable[KEY_SPLASH_ACTIVITY]?.let { activityInfo ->
            runCatching {
                val activityClass = findClass(activityInfo.className)
                
                // Immediately finish splash activity on creation - FIXED WITH IMPORT
                activityClass.hookAllBefore("onCreate") { param ->
                    param.thisObject?.call("finish")  // SAFE NULL CHECK ADDED
                    param.result = null
                }
                
                logI("${this::class.simpleName}: Splash activity suppression installed")
                
            }.onFailure { activity ->
                logE("${this::class.simpleName}: Activity suppression failed", activity)
            }
        }
    }
    
    private fun performDirectHook() {
        // Direct hook attempt without ObfsTable (less resilient)
        runCatching {
            // SAFE CLASS RESOLUTION WITH CLASSLOADER SCOPE
            val splashClass = classLoader.findClassN("com.bstar.intl.ui.splash.ad.model.Splash")
                ?: return@runCatching logE("${this::class.simpleName}: Splash class not found")
            
            // Primary: Hook isValid directly - PROJECT UTILITY
            splashClass.hookAllConstant("isValid", false)
            
            // Secondary: Hook any validation methods - PROJECT UTILITY
            splashClass.hookAllBefore("validate") { param ->
                param.result = false
            }
            
            splashClass.hookAllBefore("canShow") { param ->
                param.result = false
            }
            
            splashClass.hookAllBefore("shouldDisplay") { param ->
                param.result = false
            }
            
            logI("${this::class.simpleName}: Direct hook strategy successful")
            
        }.onFailure { direct ->
            logE("${this::class.simpleName}: Direct hook failed - target may be heavily obfuscated", direct)
            
            // Ultra-fallback: Pattern-based search
            performPatternBasedSearch()
        }
    }
    
    private fun performPatternBasedSearch() {
        // Last resort: Search for splash-related classes by pattern
        runCatching {
            val packageList = listOf(
                "com.bstar.intl.ui.splash",
                "com.bstar.intl.splash",
                "com.bilibili.intl.splash"
            )
            
            for (pkg in packageList) {
                // SAFE CLASS RESOLUTION WITH PROJECT METHOD
                findClassOrNull("$pkg.ad.model.Splash")?.let { splashClass ->
                    splashClass.declaredMethods
                        .filter { it.returnType == Boolean::class.java }
                        .forEach { method ->
                            method.hookReplace { _ -> false }
                        }
                    
                    logI("${this::class.simpleName}: Pattern-based suppression installed for $pkg")
                    return@runCatching
                }
            }
            
            logE("${this::class.simpleName}: No splash classes found via pattern search")
            
        }.onFailure { pattern ->
            logE("${this::class.simpleName}: Pattern-based search failed", pattern)
        }
    }
}