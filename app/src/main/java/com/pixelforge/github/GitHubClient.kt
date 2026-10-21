package com.pixelforge.github

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class GitHubException(message: String) : Exception(message)

data class CiRun(val id: Long, val conclusion: String, val url: String)

data class RepoInfo(val fullName: String, val defaultBranch: String, val url: String)

/** Commit file ke repo, tunggu GitHub Actions, dan ambil log error untuk Debugger. */
class GitHubClient(private val token: String, repo: String, private val branch: String) {

    private val api = "https://api.github.com/repos/${repo.trim()}"

    private val http = HttpClient(Android) {
        engine { connectTimeout = 30_000; socketTimeout = 60_000 }
    }
    private val httpNoRedirect = HttpClient(Android) {
        followRedirects = false
        engine { connectTimeout = 30_000; socketTimeout = 60_000 }
    }

    private class Resp(val code: Int, val body: String, val location: String?)

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        client: HttpClient = http
    ): Resp = callUrl(method, api + path, body, client)

    private suspend fun callUrl(
        method: HttpMethod,
        url: String,
        body: JsonObject? = null,
        client: HttpClient = http
    ): Resp = withContext(Dispatchers.IO) {
        val r = client.request(url) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header(HttpHeaders.UserAgent, "PixelForge")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        Resp(r.status.value, r.bodyAsText(), r.headers[HttpHeaders.Location])
    }

    private fun msgOf(body: String): String = runCatching {
        JsonParser.parseString(body).asJsonObject.get("message").asString
    }.getOrElse { body.take(160) }

    private fun obj(r: Resp, what: String): JsonObject {
        if (r.code !in 200..299) throw GitHubException("$what → HTTP ${r.code}: ${msgOf(r.body)}")
        return JsonParser.parseString(r.body).asJsonObject
    }

    private fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** Agent Repo: buat repo baru (dengan README awal supaya langsung siap di-commit). */
    suspend fun createRepo(name: String, description: String, isPrivate: Boolean): RepoInfo {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description.take(200))
            addProperty("private", isPrivate)
            addProperty("auto_init", true)
        }
        val r = callUrl(HttpMethod.Post, "https://api.github.com/user/repos", body)
        if (r.code in 200..299) {
            val o = JsonParser.parseString(r.body).asJsonObject
            return RepoInfo(
                o.get("full_name").asString,
                o.get("default_branch").asString,
                o.get("html_url").asString
            )
        }
        if (r.code == 422) {
            throw GitHubException("Nama repo '$name' sudah dipakai atau tidak valid. Coba nama lain.")
        }
        if (r.code == 401) throw GitHubException("Token GitHub salah atau kedaluwarsa.")
        if (r.code == 403 || r.code == 404) {
            throw GitHubException(
                "Token tidak punya izin membuat repo baru (HTTP ${r.code}: ${msgOf(r.body)}). " +
                    "Buat repo kosong manual di https://github.com/new?name=$name lalu isi nama repo lewat tombol ✎ di bar project."
            )
        }
        throw GitHubException("Gagal membuat repo → HTTP ${r.code}: ${msgOf(r.body)}")
    }

    /** Satu commit berisi semua file. Return SHA commit baru. */
    suspend fun commitFiles(files: Map<String, String>, message: String): String {
        var ref = call(HttpMethod.Get, "/git/ref/heads/$branch")
        if (ref.code == 404 || ref.code == 409) {
            // Repo masih kosong: buat commit awal lewat Contents API
            val init = JsonObject().apply {
                addProperty("message", "Initial commit")
                addProperty("content", b64("# PixelForge project\n"))
            }
            val ir = call(HttpMethod.Put, "/contents/README.md", init)
            if (ir.code !in 200..299) {
                throw GitHubException("Branch '$branch' tidak ditemukan / repo tidak bisa diakses (HTTP ${ir.code}: ${msgOf(ir.body)})")
            }
            ref = call(HttpMethod.Get, "/git/ref/heads/$branch")
        }
        val head = obj(ref, "ambil branch").getAsJsonObject("object").get("sha").asString
        val baseTree = obj(call(HttpMethod.Get, "/git/commits/$head"), "ambil commit")
            .getAsJsonObject("tree").get("sha").asString

        val entries = JsonArray()
        for ((path, content) in files) {
            val blobReq = JsonObject().apply {
                addProperty("content", b64(content))
                addProperty("encoding", "base64")
            }
            val blobSha = obj(call(HttpMethod.Post, "/git/blobs", blobReq), "upload $path").get("sha").asString
            entries.add(JsonObject().apply {
                addProperty("path", path)
                addProperty("mode", "100644")
                addProperty("type", "blob")
                addProperty("sha", blobSha)
            })
        }
        val treeReq = JsonObject().apply {
            addProperty("base_tree", baseTree)
            add("tree", entries)
        }
        val tree = obj(call(HttpMethod.Post, "/git/trees", treeReq), "buat tree").get("sha").asString
        val commitReq = JsonObject().apply {
            addProperty("message", message)
            addProperty("tree", tree)
            add("parents", JsonArray().apply { add(head) })
        }
        val commit = obj(call(HttpMethod.Post, "/git/commits", commitReq), "buat commit").get("sha").asString
        val updReq = JsonObject().apply { addProperty("sha", commit) }
        obj(call(HttpMethod.Patch, "/git/refs/heads/$branch", updReq), "update branch")
        return commit
    }

    /** Tunggu workflow untuk commit ini selesai. Null kalau repo tidak punya workflow. */
    suspend fun waitForRun(sha: String): CiRun? {
        val start = System.currentTimeMillis()
        val okSet = setOf("success", "skipped", "neutral")
        while (System.currentTimeMillis() - start < 15 * 60_000L) {
            val r = obj(call(HttpMethod.Get, "/actions/runs?head_sha=$sha&per_page=5"), "daftar build")
            val runs = r.getAsJsonArray("workflow_runs").map { it.asJsonObject }
            if (runs.isNotEmpty()) {
                if (runs.all { it.get("status").asString == "completed" }) {
                    val pick = runs.firstOrNull { it.get("conclusion").asString !in okSet } ?: runs.first()
                    return CiRun(
                        pick.get("id").asLong,
                        pick.get("conclusion").asString,
                        pick.get("html_url").asString
                    )
                }
            } else if (System.currentTimeMillis() - start > 90_000L) {
                return null
            }
            delay(10_000L)
        }
        throw GitHubException("Build belum selesai setelah 15 menit.")
    }

    /** Ringkasan log job yang gagal (baris error + ekor log). */
    suspend fun failureLog(runId: Long): String {
        val jobs = obj(call(HttpMethod.Get, "/actions/runs/$runId/jobs"), "daftar job")
            .getAsJsonArray("jobs").map { it.asJsonObject }
        val failed = jobs.firstOrNull {
            val c = it.get("conclusion")
            c != null && !c.isJsonNull && c.asString == "failure"
        } ?: return "(job yang gagal tidak ditemukan; mungkin dibatalkan)"

        val badSteps = failed.getAsJsonArray("steps").map { it.asJsonObject }
            .filter {
                val c = it.get("conclusion")
                c != null && !c.isJsonNull && c.asString == "failure"
            }
            .joinToString(", ") { it.get("name").asString }

        val r = call(HttpMethod.Get, "/actions/jobs/${failed.get("id").asLong}/logs", client = httpNoRedirect)
        val raw = when {
            r.code in 300..399 && r.location != null ->
                withContext(Dispatchers.IO) { http.get(r.location).bodyAsText() }
            r.code in 200..299 -> r.body
            else -> throw GitHubException(
                "Tidak bisa membaca log build (HTTP ${r.code}: ${msgOf(r.body)}). " +
                    "Pastikan token punya izin Actions: Read."
            )
        }
        return "Step gagal: $badSteps\n\n" + condense(raw)
    }

    private val keyRe = Regex("(?i)(\\be: |error|FAILED|What went wrong|Caused by|Exception|Unresolved|cannot find|not found)")
    private val tsRe = Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z ")

    private fun condense(raw: String): String {
        val lines = raw.lines().map { it.replace(tsRe, "") }
        val key = lines.filter { keyRe.containsMatchIn(it) }.take(80)
        val tail = lines.takeLast(40)
        return (key + listOf("[...]") + tail).joinToString("\n").take(8000)
    }

    fun close() {
        http.close()
        httpNoRedirect.close()
    }
}
