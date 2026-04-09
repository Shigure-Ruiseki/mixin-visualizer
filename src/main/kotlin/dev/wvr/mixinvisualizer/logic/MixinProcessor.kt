package dev.wvr.mixinvisualizer.logic

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtilCore
import dev.wvr.mixinvisualizer.util.BytecodeUtils
import java.io.File

class MixinProcessor(private val project: Project) {
    private val transformer = MixinTransformer()

    companion object {
        private val LOG = Logger.getInstance(MixinProcessor::class.java)
    }

    fun process(mixinPsiFile: PsiFile, showBytecode: Boolean): Pair<String, String> {
        if (mixinPsiFile !is PsiJavaFile) {
            LOG.warn("Provided file is not a Java file: ${mixinPsiFile.virtualFile?.path}")
            return "Err" to "Not a java file"
        }

        return ReadAction.compute<Pair<String, String>, RuntimeException> {
            try {
                LOG.info("=== Mixin processing started ===")
                LOG.info("Mixin file: ${mixinPsiFile.virtualFile?.path}")

                val clazz = mixinPsiFile.classes.firstOrNull()
                    ?: run {
                        LOG.warn("No class found in mixin file")
                        return@compute "" to "// Class not found"
                    }

                LOG.info("Mixin class: ${clazz.qualifiedName}")

                val targetRef = findTargetClass(clazz)
                    ?: run {
                        LOG.warn("No @Mixin target found in ${clazz.qualifiedName}")
                        return@compute "" to "// No @Mixin annotation"
                    }

                LOG.info("Mixin target: $targetRef")

                val targetPsi = JavaPsiFacade.getInstance(project)
                    .findClass(targetRef, GlobalSearchScope.allScope(project))
                    ?: run {
                        LOG.warn("Target class not found in PSI: $targetRef")
                        return@compute "" to "// Target $targetRef not found"
                    }

                LOG.info("Target PSI found: ${targetPsi.qualifiedName}")

                val targetBytes = findBytecode(targetPsi)
                    ?: run {
                        LOG.warn("Failed to resolve target bytecode: $targetRef")
                        return@compute "" to "// Original bytecode not found"
                    }

                LOG.info("Target bytecode loaded: ${targetBytes.size} bytes")

                val originalContent = if (showBytecode) {
                    LOG.info("Rendering target ASM trace")
                    BytecodeUtils.toAsmTrace(targetBytes)
                } else {
                    LOG.info("Decompiling target")
                    BytecodeUtils.decompile(targetRef, targetBytes)
                }

                val targetNode = BytecodeUtils.readClassNode(targetBytes)
                LOG.info("Target class node loaded: ${targetNode.name}")

                val mixinBytes = findBytecode(clazz)
                    ?: run {
                        LOG.warn("Mixin class bytecode missing. Project likely not compiled.")
                        return@compute originalContent to "// PLEASE COMPILE THE PROJECT FIRST (Ctrl+F9)"
                    }

                LOG.info("Mixin bytecode loaded: ${mixinBytes.size} bytes")

                val mixinNode = BytecodeUtils.readClassNode(mixinBytes)
                LOG.info("Mixin class node loaded: ${mixinNode.name}")

                LOG.info("Applying mixin transformation...")
                transformer.transform(targetNode, mixinNode)
                LOG.info("Transformation complete")

                val transformedBytes = BytecodeUtils.writeClassNode(targetNode)
                LOG.info("Transformed bytecode written: ${transformedBytes.size} bytes")

                val transformedContent = if (showBytecode) {
                    LOG.info("Rendering transformed ASM trace")
                    BytecodeUtils.toAsmTrace(transformedBytes)
                } else {
                    LOG.info("Decompiling transformed class")
                    BytecodeUtils.decompile(targetRef, transformedBytes)
                }

                LOG.info("=== Mixin processing finished successfully ===")

                originalContent to transformedContent

            } catch (e: Throwable) {
                LOG.error("Mixin processing failed", e)
                "" to "// Error: ${e.message}\n${e.stackTraceToString()}"
            }
        }
    }

    private fun findTargetClass(mixinClass: PsiClass): String? {
        LOG.info("Resolving @Mixin target for ${mixinClass.qualifiedName}")

        val ann = mixinClass.getAnnotation("org.spongepowered.asm.mixin.Mixin")
        if (ann == null) {
            LOG.warn("No @Mixin annotation found")
            return null
        }

        val value = ann.findAttributeValue("value")
        LOG.info("Mixin annotation value: ${value?.text}")

        return when (value) {
            is PsiClassObjectAccessExpression -> {
                val result = value.operand.type.canonicalText
                LOG.info("Resolved target from class object: $result")
                result
            }

            is PsiLiteralExpression -> {
                val result = value.value as? String
                LOG.info("Resolved target from literal: $result")
                result
            }

            is PsiArrayInitializerMemberValue -> {
                val first = value.initializers.firstOrNull()
                if (first is PsiClassObjectAccessExpression) {
                    val result = first.operand.type.canonicalText
                    LOG.info("Resolved target from array: $result")
                    result
                } else {
                    LOG.warn("Mixin target array empty or invalid")
                    null
                }
            }

            else -> {
                LOG.warn("Unsupported @Mixin value type: ${value?.javaClass?.name}")
                null
            }
        }
    }

    private fun findBytecode(psiClass: PsiClass): ByteArray? {
        val qName = psiClass.qualifiedName ?: return null.also {
            LOG.warn("PsiClass has no qualified name")
        }

        val classPath = qName.replace('.', '/') + ".class"

        LOG.info("Finding bytecode for: $qName")

        val vFile = PsiUtilCore.getVirtualFile(psiClass)

        LOG.info(
            "VirtualFile = ${vFile?.path}, type = ${vFile?.fileType?.name}, binary = ${vFile?.fileType?.isBinary}"
        )

        // 1. Direct binary file (.class in VFS / inside jar)
        if (vFile != null && vFile.fileType.isBinary) {
            LOG.info("Loading direct binary file: ${vFile.path}")
            return vFile.contentsToByteArray().also {
                LOG.info("Loaded ${it.size} bytes from direct binary VFS")
            }
        }

        // 2. Compiler output
        val module = vFile?.let {
            ProjectRootManager.getInstance(project).fileIndex.getModuleForFile(it)
        }

        LOG.info("Resolved module: ${module?.name}")

        val output = module?.let {
            com.intellij.openapi.roots.CompilerModuleExtension.getInstance(it)?.compilerOutputPath
        }

        LOG.info("Compiler output path: ${output?.path}")

        if (output != null) {
            val classFile = output.findFileByRelativePath(classPath)
            if (classFile != null && classFile.exists()) {
                LOG.info("Loaded from compiler output: ${classFile.path}")
                return classFile.contentsToByteArray().also {
                    LOG.info("Loaded ${it.size} bytes from compiler output")
                }
            }
        }

        // 3. IntelliJ classpath lookup (libraries / jars / attached classes)
        val classVFile = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
            .iterateContent { true } // dummy to init index safely
            .let {
                com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                JavaPsiFacade.getInstance(project)
                    .findClass(qName, GlobalSearchScope.allScope(project))
                    ?.containingFile
                    ?.virtualFile
            }

        if (classVFile != null && classVFile.fileType.isBinary) {
            LOG.info("Loaded from IntelliJ classpath binary: ${classVFile.path}")
            return classVFile.contentsToByteArray().also {
                LOG.info("Loaded ${it.size} bytes from IntelliJ classpath")
            }
        }

        // Optional: locate class root manually
        val roots = ProjectRootManager.getInstance(project)
            .orderEntries()
            .classes()
            .roots

        for (root in roots) {
            val entry = root.findFileByRelativePath(classPath)
            if (entry != null && entry.exists()) {
                LOG.info("Loaded from library root: ${entry.path}")
                return entry.contentsToByteArray().also {
                    LOG.info("Loaded ${it.size} bytes from library root")
                }
            }
        }

        // 4. ClassLoader fallback
        LOG.info("Trying classloader resource: $classPath")

        val stream = javaClass.classLoader.getResourceAsStream(classPath)
        if (stream != null) {
            LOG.info("Found classloader resource")
            return stream.readBytes().also {
                LOG.info("Loaded ${it.size} bytes from classloader")
            }
        }

        LOG.warn("Bytecode not found for $qName")
        return null
    }
}