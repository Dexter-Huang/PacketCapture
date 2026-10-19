package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Kimi 3.1.4 新对话的数字枚举 Connect JSON；正文与推理分开，未知工具流不报告成功。 */
internal class KimiDecoder : AnswerDecoder {
    override val id = "kimi-chat-connect/1"
    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" && uri.host == "www.kimi.com" &&
            uri.path == "/apiv2/kimi.gateway.chat.v1.ChatService/Chat" && uri.port in setOf(-1, 443) &&
            uri.userInfo == null && uri.fragment == null &&
            exchange.responseHeaders.firstHeader("content-type")?.substringBefore(';')?.trim()?.lowercase() == "application/connect+enum_num+json"
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val req = if (request.limited) null else request.rawBytes?.let(::kmRequest)
        val message = req?.kmObj("message")
        val blocks = message?.get("blocks")?.takeIf { it.isJsonArray }?.asJsonArray
        val prompt = blocks?.singleOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.kmObj("text")?.kmString("content")
        val issues = linkedSetOf<String>()
        val options = req?.kmObj("options")
        val supported = req != null && req.kmString("chatId") == "" && req.kmString("kimiplusId") == "" &&
            req.kmInt("scenario") == 1 && req.kmEmpty("projectId") &&
            message?.kmInt("role") == 2 && message.kmInt("scenario") == 1 &&
            message.kmString("id") == "" && message.kmString("parentId") == "" &&
            listOf("refs", "references", "error", "labels", "labelsValue").all { message.kmEmpty(it) } &&
            blocks?.size() == 1 && blocks[0].isJsonObject && blocks[0].asJsonObject.entrySet().all { (k, v) -> k in setOf("id", "parentId", "messageId", "text", "createTime") || v.isJsonNull } &&
            options?.kmString("model") == "k2d6-chat" && options.kmBool("thinking") == false &&
            options.kmBool("enablePlugin") == false && options.kmInt("reasoningEffort") == 3 &&
            options.entrySet().all { (k,v) -> k in setOf("thinking","model","enablePlugin","reasoningEffort","voiceId") || v.isJsonNull } &&
            options.kmString("voiceId") == "" && kmTools(req)
        if (!supported) issues += "unsupported_request_protocol"
        if (prompt.isNullOrBlank()) issues += "prompt_unavailable"
        val state = StreamState(prompt)
        val bytes = response.rawBytes
        if (bytes == null || bytes.size > DEFAULT_BODY_LIMIT) issues += "body_unavailable_or_limited"
        else state.accept(bytes)
        issues += state.issues
        if (request.rawBytes == null || request.limited || response.limited ||
            listOfNotNull(exchange.requestBody, exchange.responseBody).any { it.truncated || it.observedBytes > it.savedBytes })
            issues += "body_unavailable_or_limited"
        if (exchange.completion != Completion.COMPLETE || exchange.endedAt == null || exchange.requestBody == null ||
            exchange.responseBody == null || exchange.requestBody?.inProgress == true || exchange.responseBody?.inProgress == true) issues += "transport_incomplete"
        if (!exchange.error.isNullOrBlank() || exchange.status !in 200..299) issues += "transport_error"
        if (!state.done || !state.trailer || !state.messageFinished || !state.stagesFinished()) issues += "missing_terminal_marker"
        if (state.answer.isBlank()) issues += "empty_answer"
        val status = when {
            state.remoteError || "transport_error" in issues || exchange.completion == Completion.FAILED -> "failed"
            !supported || state.unknown -> "unsupported"
            issues.isEmpty() -> "complete"
            else -> "incomplete"
        }
        return CapturedAnswer(provider="kimi",sessionId=exchange.sessionId,exchangeId=exchange.id,packageName=exchange.packageName,
            startedAt=exchange.startedAt,endedAt=exchange.endedAt,decoder=id,prompt=prompt,answer=state.answer.toString(),
            reasoning=state.reasoning.toString(),status=status,diagnostics=issues.toList(),eventCount=state.count)
    }

    private class StreamState(private val prompt:String?) {
        val answer=StringBuilder()
        val reasoning=StringBuilder()
        val issues=linkedSetOf<String>()
        var unknown=false
        var remoteError=false
        var done=false
        var trailer=false
        var messageFinished=false
        var count=0
        private var offset=0
        private var chat:String?=null
        private var rootSeen=false
        private var lastRequestSeen=false
        private var ask:String?=null
        private var reply:String?=null
        private data class Stage(val parent:String,val kind:String,var status:Int)
        private val stages=linkedMapOf<String,Stage>()
        private var textId:String?=null
        private var thinkId:String?=null
        private var thinkParent:String?=null
        private var summarySeen=false
        fun stagesFinished() = stages.values.all { it.status==2 }
        private fun schema(ok:Boolean, issue:String="unrecognized_event"):Boolean {
            if(!ok) { unknown=true;issues+=issue };return ok
        }
        fun accept(bytes:ByteArray) {
            var cursor=0
            while(cursor<bytes.size) {
                if(bytes.size-cursor<5) { issues+="pending_connect_frame";break }
                val flag=bytes[cursor].toInt() and 255
                val size=ByteBuffer.wrap(bytes,cursor+1,4).int.toLong() and 0xffffffffL
                cursor+=5
                if(size>bytes.size-cursor) { issues+="pending_connect_frame";break }
                count++
                val text=runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,cursor,size.toInt())).toString() }.getOrNull()
                cursor+=size.toInt()
                val p=text?.let(::kmJson)
                if(!schema(!trailer && p!=null)) continue
                if(p!!.has("error") && !p.get("error").isJsonNull) { remoteError=true;issues+="remote_error" }
                if(flag==2) {
                    trailer=true
                    schema(p.size()==0 || remoteError,"unsupported_connect_trailer")
                    if(cursor!=bytes.size) schema(false,"event_after_done")
                    continue
                }
                if(!schema(flag==0,"unsupported_connect_flags")) continue
                event(p)
            }
        }
        private fun event(p:JsonObject) {
            if(!schema(!done,"event_after_done")) return
            if (listOfNotNull(p.kmObj("message"),p.kmObj("block")).any { !it.kmEmpty("error") || !it.kmEmpty("exception") }) {
                remoteError=true;issues+="remote_error"
            }
            if(p.has("heartbeat")) {
                schema(p.kmObj("heartbeat")?.size()==0 && p.keySet().all { it in setOf("heartbeat","eventOffset") } &&
                    (!p.has("eventOffset") || p.kmInt("eventOffset")==offset),"stream_sequence_mismatch")
                return
            }
            if(!schema(p.kmInt("eventOffset")==offset+1,"stream_sequence_mismatch")) return
            offset++
            val mask=p.kmString("mask")
            if(p.has("done")) {
                schema(p.kmObj("done")?.size()==0 && p.keySet()==setOf("eventOffset","done"))
                done=true;return
            }
            val op=p.kmInt("op")
            if(!schema(p.keySet().all { it in setOf("op","mask","eventOffset","chat","message","block") })) return
            when(mask) {
                null -> when {
                    p.has("chat") -> {
                        val c=p.kmObj("chat")
                        if(schema(op==1 && chat==null && !c?.kmString("id").isNullOrBlank())) chat=c!!.kmString("id")
                    }
                    p.has("message") -> {
                        val m=p.kmObj("message")
                        if(schema(op==1 && !rootSeen && chat!=null && m!=null && m.kmString("id")==chat && m.kmInt("role")==1 &&
                                m.kmInt("status")==2 && m.kmInt("scenario")==1,"stream_identity_mismatch")) rootSeen=true
                    }
                    else -> schema(false)
                }
                "chat.lastRequest" -> {
                    val c=p.kmObj("chat");val req=c?.kmObj("lastRequest");val options=req?.kmObj("options")
                    if(schema(op==1 && !lastRequestSeen && chat!=null && c!=null && c.kmString("id")==chat && req?.kmInt("scenario")==1 &&
                            options?.kmString("model")=="k2d6-chat" && options.kmBool("enablePlugin")==false &&
                            options.kmBool("thinking")!=null && options.kmInt("reasoningEffort") in setOf(3,5) && kmTools(req),"unsupported_response_protocol")) lastRequestSeen=true
                }
                "chat.name" -> schema(op==1 && chat!=null && p.kmObj("chat")?.kmString("id")==chat && p.kmObj("chat")?.kmString("name")!=null)
                "message" -> {
                    val m=p.kmObj("message")
                    if(!schema(op==1 && rootSeen && lastRequestSeen && m!=null && m.kmInt("scenario")==1 &&
                            !m.kmString("id").isNullOrBlank() && m.kmString("id")!=chat && m.kmEmpty("error") && m.kmBool("isGoal")!=true,"stream_identity_mismatch")) return
                    when(m!!.kmInt("role")) {
                        2 -> {
                            val blocks=m.get("blocks")?.takeIf { it.isJsonArray }?.asJsonArray
                            if(schema(ask==null && reply==null && m.kmString("parentId")==chat && m.kmInt("status")==2 && blocks?.size()==1 &&
                                    blocks[0].isJsonObject && blocks[0].asJsonObject.kmObj("text")?.kmString("content")==prompt,"stream_identity_mismatch")) ask=m.kmString("id")
                        }
                        3 -> if(schema(reply==null && ask!=null && m.kmString("parentId")==ask && m.kmInt("status")==1 &&
                                m.kmString("id")!=ask,"stream_identity_mismatch")) reply=m.kmString("id")
                        else -> schema(false,"unsupported_message_role")
                    }
                }
                "message.status" -> if(schema(op==1 && reply!=null && !messageFinished && p.kmObj("message")?.kmString("id")==reply &&
                        p.kmObj("message")?.kmInt("status")==2,"stream_identity_mismatch")) messageFinished=true
                "block.multiStage", "block.stage" -> stage(p,mask,op)
                "block.text", "block.text.content", "block.think", "block.think.content" -> text(p,mask,op)
                "block.think.summary" -> {
                    val b=p.kmObj("block");val think=b?.kmObj("think")
                    // 已验证的推理摘要标题是元数据，不替换或追加已采集推理。
                    if(schema(op==1 && !summarySeen && !messageFinished && thinkId!=null && b!=null &&
                            b.kmString("id")==thinkId && b.kmString("parentId")==thinkParent &&
                            (!b.has("messageId") || b.kmString("messageId")==reply) && stages[thinkParent]?.status==2 &&
                            !think?.kmString("summary").isNullOrBlank() && think!!.keySet()==setOf("summary") &&
                            b.entrySet().all { (k,v) -> k in setOf("id","parentId","messageId","think","createTime") || v.isJsonNull },
                            "unsupported_thinking_summary")) summarySeen=true
                }
                else -> schema(false,"unsupported_component")
            }
        }
        private fun stage(p:JsonObject,mask:String,op:Int?) {
            val b=p.kmObj("block");val id=b?.kmString("id");val parent=b?.kmString("parentId")
            val kind=mask.substringAfter('.')
            val data=if(kind=="stage") b?.kmObj(kind) else b?.kmObj(kind)?.get("stages")?.takeIf { it.isJsonArray && it.asJsonArray.size()==1 }
                ?.asJsonArray?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
            if(!schema(op==1 && reply!=null && !messageFinished && b?.kmString("messageId")==reply && !id.isNullOrBlank() &&
                    parent!=null && data?.kmInt("name")==11 && data.kmInt("status") in setOf(1,2),"unsupported_stage_protocol")) return
            val status=data!!.kmInt("status")!!;val previous=stages[id]
            if(previous==null) {
                if(!schema(status==1 && (if(kind=="multiStage") parent=="" && stages.isEmpty() else stages[parent]?.kind=="multiStage"),"stream_identity_mismatch")) return
                stages[id!!]=Stage(parent!!,kind,status)
            } else if(schema(previous.kind==kind && previous.parent==parent && previous.status==1 && status==2,"stream_identity_mismatch")) previous.status=2
        }
        private fun text(p:JsonObject,mask:String,op:Int?) {
            val b=p.kmObj("block");val id=b?.kmString("id");val parent=b?.kmString("parentId")
            val thinking=mask.startsWith("block.think");val field=if(thinking) "think" else "text"
            val content=b?.kmObj(field)?.kmString("content")
            if(!schema(reply!=null && !messageFinished && !id.isNullOrBlank() && content!=null && parent!=null &&
                    (!b.has("messageId") || b.kmString("messageId")==reply) &&
                    b.entrySet().all { (k,v) -> k in setOf("id","parentId","messageId",field,"createTime") || v.isJsonNull },"stream_identity_mismatch")) return
            if(mask=="block.$field") {
                if(!schema(op==1 && (if(thinking) thinkId==null && stages[parent]?.kind=="stage" && stages[parent]?.status==1
                    else textId==null && parent=="" && stagesFinished()),"unsupported_text_operation")) return
                if(thinking) { thinkId=id;thinkParent=parent } else textId=id
            } else if(!schema(op==2 && (if(thinking) id==thinkId && parent==thinkParent && stages[parent]?.status==1
                    else id==textId && parent==""),"unsupported_text_operation")) return
            (if(thinking) reasoning else answer).append(content)
        }
    }
}

private fun kmJson(value:String):JsonObject? = runCatching { JsonParser.parseString(value).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
private fun kmRequest(bytes:ByteArray):JsonObject? = runCatching {
    if(bytes.size<5 || bytes.size>DEFAULT_BODY_LIMIT || bytes[0]!=0.toByte() ||
        (ByteBuffer.wrap(bytes,1,4).int.toLong() and 0xffffffffL)!=bytes.size-5L) return null
    kmJson(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes,5,bytes.size-5)).toString())
}.getOrNull()
private fun JsonObject.kmObj(k:String)=get(k)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.kmString(k:String)=get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.kmInt(k:String)=get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString()?.toIntOrNull()
private fun JsonObject.kmBool(k:String)=get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
private fun JsonObject.kmEmpty(k:String)=get(k).let { it==null || it.isJsonNull || it.isJsonArray && it.asJsonArray.size()==0 }
private fun kmTools(req:JsonObject)=req.get("tools")?.let { it.isJsonArray && it.asJsonArray.size()==2 &&
    it.asJsonArray.map { v -> v.takeIf { it.isJsonObject }?.asJsonObject?.kmInt("type") }==listOf(5,1) &&
    it.asJsonArray.all { v -> v.asJsonObject.entrySet().all { (k,x) -> k=="type" || x.isJsonNull || k=="name" && x.isJsonPrimitive && x.asString=="" } } }==true
