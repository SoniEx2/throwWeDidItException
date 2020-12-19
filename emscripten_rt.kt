/**
 * Copyright 2020 Soni L.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 *
 * This code adapts code from the emscripten project. Emscripten's license
 * follows:
 *
Emscripten is available under 2 licenses, the MIT license and the
University of Illinois/NCSA Open Source License.

Both are permissive open source licenses, with little if any
practical difference between them.

The reason for offering both is that (1) the MIT license is
well-known, while (2) the University of Illinois/NCSA Open Source
License allows Emscripten's code to be integrated upstream into
LLVM, which uses that license, should the opportunity arise.

The full text of both licenses follows.

==============================================================================

Copyright (c) 2010-2014 Emscripten authors, see AUTHORS file.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

==============================================================================

Copyright (c) 2010-2014 Emscripten authors, see AUTHORS file.
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the
"Software"), to deal with the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

    Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimers.

    Redistributions in binary form must reproduce the above
    copyright notice, this list of conditions and the following disclaimers
    in the documentation and/or other materials provided with the
    distribution.

    Neither the names of Mozilla,
    nor the names of its contributors may be used to endorse
    or promote products derived from this Software without specific prior
    written permission. 

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE SOFTWARE.
 */

package space.autistic.emscripten_rt

import wasm_rt_impl.Memory
import wasm_rt_impl.Table
import wasm_rt_impl.ModuleRegistry

import kotlin.reflect.KMutableProperty0
import kotlin.Function

import java.io.ByteArrayOutputStream

class NumberEmscriptenException(val number: Int) : RuntimeException(null, null, false, false)

object LongjmpException : RuntimeException(null, null, false, false)

/**
 * An instance of MemoryHolder specifies the memory layout the wasm module will
 * have access to, and holds the Memory object. Note that memory size must be a
 * multiple of 65536, all values are in bytes, the real stack size may be
 * smaller depending on the size of the module's data section, and the stack
 * must have the correct alignment. It is up to the calling code to set an
 * appropriate stack size.
 * @param startMemory The initial amount of memory to be allocated.
 * @param maxMemory The maximum amount of memory to be allowed.
 * @param maxStack The size of the stack. Must be smaller than startMemory.
 */
public class MemoryHolder(startMemory: Int, maxMemory: Int, val maxStack: Int) {
    public var memory = Memory(0, 0)

    init {
        if ((startMemory or maxMemory) < 0) {
            // JVM limitation, memory may only be as big as 2GB-64KB
            throw IllegalArgumentException("Memory size must be non-negative")
        }
        if ((startMemory and 0xFFFF) != 0 || (maxMemory and 0xFFFF) != 0) {
            throw IllegalArgumentException("Memory size must be a multiple of 65536")
        }
        if ((maxStack and 0x7) != 0) {
            // TODO figure out actual alignment requirements
            throw IllegalArgumentException("Stack must be aligned on an 8-byte boundary")
        }
        if (maxStack >= startMemory) {
            throw IllegalArgumentException("Stack size must be smaller than initial memory size.")
        }
        wasm_rt_impl.allocate_memory(this::memory, startMemory/65536, maxMemory/65536)
    }
}

/**
 * An instance of TableHolder specifies a table size the wasm module will have
 * access to, and holds the Table object.
 * @param initialSize The number of initial table slots.
 * @param maxSize The maximum number of table slots.
 */
public class TableHolder(initialSize: Int, maxSize: Int) {
    public var table = Table(0, 0)

    init {
        if ((initialSize or maxSize) < 0) {
            throw IllegalArgumentException("Table size must be non-negative")
        }
        wasm_rt_impl.allocate_table(this::table, initialSize, maxSize)
    }
}

/**
 * The environment used by wasi. Holds environment variables, program
 * arguments, etc.
 */
public class Environment {
    public val environment: MutableList<String> = ArrayList()
    public val arguments: MutableList<String> = ArrayList()
}

// for use with the GOT
private class IntHolder(initial: Int) {
    var x: Int = initial
}

/**
 * WASI errno.
 */
public enum class WasiErrno {
    SUCCESS;

    companion object {
        const val SIZE = 2
        const val ALIGNMENT = 2
    }
}

open class EmscriptenModuleRegistry(val mainModuleName: String, val memoryLayout: MemoryHolder, val tableLayout: TableHolder, private val env: Environment) : ModuleRegistry() {
    /* "constants" */
    private val func_type_vii = wasm_rt_impl.register_func_type(2, 0, Int::class, Int::class)

    /* data */
    private var tempRet0 = 0

    /* imports */
    private lateinit var stackSave: () -> Int
    private lateinit var stackRestore: (Int) -> Unit
    private lateinit var setThrew: (Int, Int) -> Unit

    // GOT/relocatable
    // TODO
    //private var gOTMem_i: MutableMap<String, IntHolder> = HashMap()

    /* exports */
    // NOTE: must be aligned.
    private var stackPointer = memoryLayout.maxStack
    // NOTE: must be aligned.
    private var memoryBase = 0
    // NOTE: starts at 1, because 0 is reserved for the null pointer.
    private var tableBase = 1

    private var memory by memoryLayout::memory
    private var table by tableLayout::table

    // TODO implement GOT
    /* export: 'GOT.mem' '__heap_base' */
    //private var w2k_Z___heap_baseZ_i: Int by moduleRegistry.importGlobal("Z_GOTZ2Emem", "Z___heap_baseZ_i");

    private fun Z_emscripten_longjmpZ_vii(env: Int, value: Int) {
        setThrew(env, if (value != 0) value else 1)
        throw LongjmpException
    }

    private fun Z_getTempRet0Z_iv(): Int = tempRet0

    private fun Z_invoke_viiZ_viii(index: Int, a1: Int, a2: Int) {
        val sp = stackSave()
        var toRestore = true
        try {
            wasm_rt_impl.CALL_INDIRECT<(Int, Int) -> Unit>(table, func_type_vii, index)(a1, a2)
            toRestore = false
            return
        } catch (e: NumberEmscriptenException) {
            // "empty" catch.
            // this runs the finally block, and then goes through to the setThrew
            // but for any other exception, it'd run the finally *but not* setThrew!
            // which is important for properly handling wasm exceptions and whatnot.
        } catch (e: LongjmpException) {
            // "empty" catch.
            // this runs the finally block, and then goes through to the setThrew
            // but for any other exception, it'd run the finally *but not* setThrew!
            // which is important for properly handling wasm exceptions and whatnot.
        } finally {
            if (toRestore) {
                stackRestore(sp)
            }
        }
        setThrew(1, 0)
    }

    private fun Z_setTempRet0Z_vi(value: Int) {
        tempRet0 = value
    }

    private fun Z_args_sizes_getZ_iii(pArgc: Int, pArgvBufSize: Int): Int {
        // TODO handle character encoding errors?
        val argc = env.arguments.size
        val buf = StringBuilder()
        env.arguments.forEach(fun(s: String) {
            buf.append(s)
            buf.append('\u0000')
        })
        val argBufSize = buf.toString().toByteArray(Charsets.UTF_8).size

        memory.i32_store(pArgc.toLong(), argc)
        memory.i32_store(pArgvBufSize.toLong(), argBufSize)

        return WasiErrno.SUCCESS.ordinal
    }

    private fun Z_args_getZ_iii(argv: Int, argvBuf: Int): Int {
        // TODO handle character encoding errors?
        var base = argvBuf
        var arg = 0
        env.arguments.forEach(fun(s: String) {
            memory.i32_store((argv + arg).toLong(), base)
            base += EmscriptenSupport.writeCString(memory, base, s)
            arg += 4
        })
        return WasiErrno.SUCCESS.ordinal
    }

    private fun Z_environ_sizes_getZ_iii(pEnvironc: Int, pEnvironBufSize: Int): Int {
        // TODO handle character encoding errors?
        val environc = env.environment.size
        val buf = StringBuilder()
        env.environment.forEach(fun(s: String) {
            buf.append(s)
            buf.append('\u0000')
        })
        val environBufSize = buf.toString().toByteArray(Charsets.UTF_8).size

        memory.i32_store(pEnvironc.toLong(), environc)
        memory.i32_store(pEnvironBufSize.toLong(), environBufSize)

        return WasiErrno.SUCCESS.ordinal

    }

    private fun Z_environ_getZ_iii(environ: Int, environBuf: Int): Int {
        // TODO handle character encoding errors?
        var base = environBuf
        var envvar = 0
        env.environment.forEach(fun(s: String) {
            memory.i32_store((environ + envvar).toLong(), base)
            base += EmscriptenSupport.writeCString(memory, base, s)
            envvar += 4
        })
        return WasiErrno.SUCCESS.ordinal
    }

    override open fun <T> exportFunc(modname: String, fieldname: String, value: Function<T>) {
        if (modname == mainModuleName) {
            when (fieldname) {
                "Z_stackSaveZ_iv" -> stackSave = value as () -> Int
                "Z_setThrewZ_vii" -> setThrew = value as (Int, Int) -> Unit
                "Z_stackRestoreZ_vi" -> stackRestore = value as (Int) -> Unit
            }
        }
        return super.exportFunc(modname, fieldname, value)
    }

    override open fun <T> importGlobal(modname: String, fieldname: String): KMutableProperty0<T> {
        try {
            return super.importGlobal(modname, fieldname)
        } catch (e: NullPointerException) {
            if (modname == "Z_GOTZ2Emem") {
                if (fieldname.endsWith("Z_i")) {
                    val holder = IntHolder(0)
                    // TODO GOT (properly)
                    super.exportGlobal(modname, fieldname, holder::x)
                    return super.importGlobal(modname, fieldname)
                }
            }
            throw e
        }
    }

    init {
        // related to memory import
        super.exportMemory("Z_env", "Z_memory", this::memory)
        super.exportTable("Z_env", "Z___indirect_function_table", this::table)

        // related to relocatable
        super.exportGlobal("Z_env", "Z___stack_pointerZ_i", this::stackPointer);
        super.exportGlobal("Z_env", "Z___memory_baseZ_i", this::memoryBase)
        super.exportGlobal("Z_env", "Z___table_baseZ_i", this::tableBase)

        // related to setjmp/longjmp
        super.exportFunc("Z_env", "Z_emscripten_longjmpZ_vii", this::Z_emscripten_longjmpZ_vii)
        super.exportFunc("Z_env", "Z_getTempRet0Z_iv", this::Z_getTempRet0Z_iv)
        super.exportFunc("Z_env", "Z_invoke_viiZ_viii", this::Z_invoke_viiZ_viii)
        super.exportFunc("Z_env", "Z_setTempRet0Z_vi", this::Z_setTempRet0Z_vi)

        // related to WASI (environment)
        super.exportFunc("Z_wasi_snapshot_preview1", "Z_environ_sizes_getZ_iii", this::Z_environ_sizes_getZ_iii)
        super.exportFunc("Z_wasi_snapshot_preview1", "Z_environ_getZ_iii", this::Z_environ_getZ_iii)
        super.exportFunc("Z_wasi_snapshot_preview1", "Z_args_sizes_getZ_iii", this::Z_args_sizes_getZ_iii)
        super.exportFunc("Z_wasi_snapshot_preview1", "Z_args_getZ_iii", this::Z_args_getZ_iii)
    }

    public fun runCtors() {
        val ctors: () -> Unit = super.importFunc(mainModuleName, "Z___wasm_call_ctorsZ_vv")
        ctors()
    }
}

public object EmscriptenSupport {
    /**
     * Reads a NUL-terminated C string from mem, starting at address base. The
     * string is parsed as UTF-8.
     */
    fun readCString(mem: Memory, base: Int): String {
        var pos = base.toLong()
        val buf = ByteArrayOutputStream()
        while (true) {
            val byte = mem.i32_load8_s(pos++)
            if (byte == 0) {
                break
            }
            buf.write(byte)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Reads a NUL-terminated C string from mem, starting at address base, in
     * a buffer of size maxLen. The string is parsed as UTF-8.
     */
    fun readCString(mem: Memory, base: Int, maxLen: Int): String {
        val buf = ByteArrayOutputStream()
        for (pos in (base.toLong() until base.toLong() + maxLen.toLong())) {
            val byte = mem.i32_load8_s(pos)
            if (byte == 0) {
                break
            }
            buf.write(byte)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Reads an array of bytes from mem.
     */
    fun readBytes(mem: Memory, base: Int, len: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        for (pos in (base.toLong() until base.toLong() + len.toLong())) {
            buf.write(mem.i32_load8_s(pos))
        }
        return buf.toByteArray()
    }

    /**
     * Writes an array of bytes to mem.
     */
    fun writeBytes(mem: Memory, base: Int, b: ByteArray) {
        var bpos = 0
        for (pos in (base.toLong() until base.toLong() + b.size.toLong())) {
            mem.i32_store8(pos, b[bpos++].toInt())
        }
    }

    /**
     * Writes a C string to mem at the given base. The string is written as
     * UTF-8 and a NUL terminator is appended. Returns the number of bytes
     * written, including the NUL terminator. The string may contain embedded
     * NULs.
     */
    fun writeCString(mem: Memory, base: Int, s: String): Int {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeBytes(mem, base, bytes)
        mem.i32_store8(base.toLong() + bytes.size.toLong(), 0)
        return bytes.size + 1
    }
}
