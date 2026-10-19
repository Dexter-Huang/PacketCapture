package com.packetcapture.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 合成 Connect 帧；测试真实 AnswerReader 入口，避免只测孤立字符串拼接。 */
class KimiDecoderTest {
    private val gson = Gson()
    private val reqRef = BodyRef("request", 100, 100)
    private val resRef = BodyRef("response", 2000, 2000)
    private fun exchange() = HttpExchange(id="e1",sessionId="s1",connectionId="c1",packageName="com.moonshot.kimichat",
        method="POST",url="https://www.kimi.com/apiv2/kimi.gateway.chat.v1.ChatService/Chat",protocol="HTTP/2",
        startedAt=100,endedAt=200,status=200,completion=Completion.COMPLETE,requestBody=reqRef,responseBody=resRef,
        requestHeaders=listOf(Header("Content-Type","application/connect+enum_num+json")),
        responseHeaders=listOf(Header("Content-Type","application/connect+enum_num+json")))
    private fun request(prompt:String=" 合成问题🌈 ", chat:String="", thinking:Boolean=false) = gson.toJson(mapOf(
        "chatId" to chat,"kimiplusId" to "","scenario" to 1,
        "message" to mapOf("id" to "","parentId" to "","role" to 2,"scenario" to 1,
            "blocks" to listOf(mapOf("id" to "","text" to mapOf("content" to prompt))),"references" to emptyList<Any>()),
        "options" to mapOf("thinking" to thinking,"model" to "k2d6-chat","enablePlugin" to false,"reasoningEffort" to 3,"voiceId" to ""),
        "tools" to listOf(mapOf("type" to 5),mapOf("type" to 1))))
    private fun frame(payload:Any, flag:Int=0):ByteArray {
        val bytes=gson.toJson(payload).toByteArray()
        return byteArrayOf(flag.toByte())+ByteBuffer.allocate(4).putInt(bytes.size).array()+bytes
    }
    private fun event(offset:Int, mask:String?=null, op:Int?=null, vararg fields:Pair<String,Any>) =
        linkedMapOf<String,Any>("eventOffset" to offset).apply { if(mask!=null) put("mask",mask);if(op!=null) put("op",op);putAll(fields) }
    private fun events(prompt:String=" 合成问题🌈 "):List<Map<String,Any>> = listOf(
        mapOf("heartbeat" to emptyMap<String,Any>()),
        event(1,op=1,fields=arrayOf("chat" to mapOf("id" to "chat","name" to "元数据"))),
        event(2,"chat.lastRequest",1,"chat" to mapOf("id" to "chat","lastRequest" to mapOf("scenario" to 1,
            "options" to mapOf("thinking" to true,"model" to "k2d6-chat","enablePlugin" to false,"reasoningEffort" to 5),
            "tools" to listOf(mapOf("type" to 5),mapOf("type" to 1))))),
        event(3,op=1,fields=arrayOf("message" to mapOf("id" to "chat","role" to 1,"status" to 2,"scenario" to 1))),
        event(4,"message",1,"message" to mapOf("id" to "ask","parentId" to "chat","role" to 2,"status" to 2,"scenario" to 1,
            "blocks" to listOf(mapOf("text" to mapOf("content" to prompt))))),
        event(5,"message",1,"message" to mapOf("id" to "reply","parentId" to "ask","role" to 3,"status" to 1,"scenario" to 1)),
        mapOf("eventOffset" to 5,"heartbeat" to emptyMap<String,Any>()),
        event(6,"block.multiStage",1,"block" to mapOf("id" to "1","parentId" to "","messageId" to "reply",
            "multiStage" to mapOf("stages" to listOf(mapOf("name" to 11,"status" to 1))))),
        event(7,"block.stage",1,"block" to mapOf("id" to "2","parentId" to "1","messageId" to "reply","stage" to mapOf("name" to 11,"status" to 1))),
        event(8,"block.think",1,"block" to mapOf("id" to "3","parentId" to "2","think" to mapOf("content" to "推理开头"))),
        event(9,"block.think.content",2,"block" to mapOf("id" to "3","parentId" to "2","think" to mapOf("content" to "🌈"))),
        event(10,"block.multiStage",1,"block" to mapOf("id" to "1","parentId" to "","messageId" to "reply",
            "multiStage" to mapOf("stages" to listOf(mapOf("name" to 11,"status" to 2))))),
        event(11,"block.stage",1,"block" to mapOf("id" to "2","parentId" to "1","messageId" to "reply","stage" to mapOf("name" to 11,"status" to 2))),
        event(12,"block.text",1,"block" to mapOf("id" to "4","parentId" to "","text" to mapOf("content" to "**正文**"))),
        event(13,"block.text.content",2,"block" to mapOf("id" to "4","parentId" to "","text" to mapOf("content" to "🌈\n结尾"))),
        event(14,"message.status",1,"message" to mapOf("id" to "reply","status" to 2)),
        event(15,fields=arrayOf("done" to emptyMap<String,Any>())))
    private fun stream(events:List<Map<String,Any>> = events(), trailer:ByteArray=frame(emptyMap<String,Any>(),2)) =
        events.fold(byteArrayOf()) { bytes, value -> bytes+frame(value) }+trailer
    private suspend fun read(bytes:ByteArray=stream(), req:String=request(), e:HttpExchange=exchange(),
        limited:Boolean=false, selector:AnswerSelector=AnswerSelector(provider="kimi"), duplicate:Boolean=false,
        requestBytes:ByteArray=frame(JsonParser.parseString(req))):AnswerBatch {
        val repo=object:CaptureRepository {
            override suspend fun session(id:String)=CaptureSession(id="s1",title="合成",packages=listOf("com.moonshot.kimichat"))
            override suspend fun allExchanges(sessionId:String)=if(duplicate) listOf(e,e.copy(id="e2",startedAt=300)) else listOf(e)
            override suspend fun allConnections(sessionId:String)=emptyList<ConnectionRecord>()
            override fun sessions(query:String):Flow<List<CaptureSession>> = error("unused")
            override fun exchanges(sessionId:String?,filter:ExchangeFilter,limit:Int):Flow<List<HttpExchange>> = error("UI窗口不可用于重建")
            override fun connections(sessionId:String?)=flowOf(emptyList<ConnectionRecord>())
            override fun exchange(id:String):Flow<HttpExchange?> = error("unused")
            override suspend fun connection(id:String):ConnectionRecord? = error("unused")
            override suspend fun saveSession(session:CaptureSession)=error("unused")
            override suspend fun saveConnection(connection:ConnectionRecord)=error("unused")
            override suspend fun saveExchange(exchange:HttpExchange)=error("unused")
            override suspend fun deleteSession(id:String)=error("unused")
            override suspend fun clearStorage()=error("unused")
            override suspend fun recoverInterrupted()=error("unused")
        }
        val bodies=object:BodyStore {
            override suspend fun preview(ref:BodyRef?,headers:List<Header>) = if(ref?.key=="request") BodyPreview("请求也包含二进制帧头",false,false,rawBytes=requestBytes)
                else BodyPreview("不能用于解析的界面预览",false,limited,rawBytes=bytes)
            override fun open(sessionId:String,exchangeId:String,part:BodyPart,limit:Long,totalLimit:Long):BodySink=error("unused")
            override suspend fun read(ref:BodyRef,maxBytes:Int):ByteArray=error("必须使用保留的解压字节")
            override suspend fun deleteSession(sessionId:String)=error("unused")
            override suspend fun clearAll()=error("unused")
            override suspend fun usedBytes():Long=error("unused")
            override suspend fun recoverOrphans(liveSessionIds:Set<String>)=error("unused")
        }
        return AnswerReader(repo,bodies).read("s1",selector)
    }
    @Test fun readerReconstructsRawConnectAnswerAndSeparatesReasoning() = runTest {
        val batch=read();assertEquals("unique_complete",batch.selectionStatus)
        val a=batch.results.single();assertEquals("kimi-chat-connect/1",a.decoder)
        assertEquals(" 合成问题🌈 ",a.prompt);assertEquals("**正文**🌈\n结尾",a.answer);assertEquals("推理开头🌈",a.reasoning)
        assertEquals(events().size+1,a.eventCount);assertTrue(a.diagnostics.isEmpty());assertEquals("not_collected",a.referenceCaptureStatus)
        assertEquals("verified_sample",PlatformCatalog.forPackage("com.moonshot.kimichat")?.support)
    }
    @Test fun missingDoneTrailerAndIncompleteFramesNeverBecomeComplete() = runTest {
        for(bytes in listOf(stream(events().dropLast(1)),stream(trailer=byteArrayOf()),stream().dropLast(1).toByteArray(),stream()+byteArrayOf(0),
            stream(events().filterNot { it["mask"]=="message.status" }))) assertNotEquals("unique_complete",read(bytes).selectionStatus)
    }
    @Test fun reasoningSummaryMetadataDoesNotReplaceOrAppendReasoning() = runTest {
        fun summarized(block:Map<String,Any>, op:Int=1):ByteArray {
            val base=events().toMutableList()
            base.add(base.lastIndex-1,event(0,"block.think.summary",op,"block" to block))
            var offset=0
            return stream(base.map { if(it.containsKey("heartbeat")) it else it+("eventOffset" to ++offset) })
        }
        val block=mapOf("id" to "3","parentId" to "2","think" to mapOf("summary" to "推理摘要标题"))
        val good=read(summarized(block));assertEquals("unique_complete",good.selectionStatus)
        assertEquals("推理开头🌈",good.results.single().reasoning)
        assertEquals("**正文**🌈\n结尾",good.results.single().answer)
        for(bad in listOf(block+("id" to "other"),block+("parentId" to "other"),
            block+("think" to mapOf("summary" to "标题","content" to "不允许替换"))))
            assertNotEquals("unique_complete",read(summarized(bad)).selectionStatus)
        assertNotEquals("unique_complete",read(summarized(block,2)).selectionStatus)
    }
    @Test fun protocolChangesCompressionAndRemoteErrorsAreRejected() = runTest {
        assertNotEquals("unique_complete",read(stream()+frame(mapOf("heartbeat" to emptyMap<String,Any>()))).selectionStatus)
        assertNotEquals("unique_complete",read(frame(mapOf("done" to emptyMap<String,Any>()),1)).selectionStatus)
        assertEquals("unique_failed",read(stream(trailer=frame(mapOf("error" to mapOf("code" to "internal")),2))).selectionStatus)
        assertNotEquals("unique_complete",read(stream(events().map { if(it["mask"]=="block.text.content") it+("mask" to "block.future") else it })).selectionStatus)
        assertNotEquals("unique_complete",read(stream(events().map { if(it["mask"]=="block.text.content") it+("op" to 1) else it })).selectionStatus)
    }
    @Test fun identitySequenceAndCurrentQuestionMustMatch() = runTest {
        assertNotEquals("unique_complete",read(req=request("different")).selectionStatus)
        for(mask in listOf("message","message.status","block.text.content")) {
            val changed=events().map { v -> if(v["mask"]!=mask) v else v+((if(mask.startsWith("block")) "block" else "message") to
                (if(mask.startsWith("block")) mapOf("id" to "other","parentId" to "","text" to mapOf("content" to "bad"))
                else mapOf("id" to "other","role" to 3,"parentId" to "other","status" to 2,"scenario" to 1))) }
            assertNotEquals(mask,"unique_complete",read(stream(changed)).selectionStatus)
        }
        val broken=events().map { if(it["eventOffset"]==13) it+("eventOffset" to 99) else it }
        assertNotEquals("unique_complete",read(stream(broken)).selectionStatus)
    }
    @Test fun originalTransportAndBodyQualityGatesRemainMandatory() = runTest {
        for(e in listOf(exchange().copy(completion=Completion.ACTIVE),exchange().copy(endedAt=null),exchange().copy(responseBody=resRef.copy(inProgress=true)),
            exchange().copy(responseBody=resRef.copy(truncated=true)),exchange().copy(responseBody=resRef.copy(observedBytes=2001))))
            assertNotEquals("unique_complete",read(e=e).selectionStatus)
        assertEquals("unique_failed",read(e=exchange().copy(completion=Completion.FAILED,error="合成取消")).selectionStatus)
        assertNotEquals("unique_complete",read(limited=true).selectionStatus)
    }
    @Test fun exactSelectorsAndEndpointAreEnforcedWithoutExportingCredentials() = runTest {
        assertEquals("ambiguous",read(duplicate=true).selectionStatus)
        assertEquals("unique_complete",read(duplicate=true,selector=AnswerSelector(provider="kimi",exchangeId="e2",startedAfter=300)).selectionStatus)
        assertEquals("none",read(selector=AnswerSelector(provider="kimi",prompt="合成问题🌈")).selectionStatus)
        for(url in listOf(exchange().url.replace("www.kimi.com","evil.test"),exchange().url+"/",exchange().url.replace("/Chat","/ListMessages"),
            exchange().url.replace("https:","http:"),exchange().url.replace("www.kimi.com","user@www.kimi.com")))
            assertNotEquals("unique_complete",read(e=exchange().copy(url=url)).selectionStatus)
        assertFalse(AnswerJson.encode(read(e=exchange().copy(requestHeaders=exchange().requestHeaders+Header("Authorization","synthetic-secret")))).contains("synthetic-secret"))
    }
    @Test fun unverifiedModesAttachmentsAndToolsCannotPass() = runTest {
        for(req in listOf(request(chat="old-chat"),request().replace("k2d6-chat","future-model"),request().replace("\"enablePlugin\":false","\"enablePlugin\":true"),
            request().replace("\"type\":5","\"type\":99"),request().replace("\"references\":[]","\"references\":[\"file\"]")))
            assertNotEquals("unique_complete",read(req=req).selectionStatus)
    }
    @Test fun binaryRequestEnvelopeAndMalformedOneofsCannotCrashOrAuthorizeAnAnswer() = runTest {
        for(bytes in listOf(byteArrayOf(),request().toByteArray(),frame(JsonParser.parseString(request()),1),
            frame(JsonParser.parseString(request()))+byteArrayOf(0),frame(listOf("not-object"))))
            assertNotEquals("unique_complete",read(requestBytes=bytes).selectionStatus)
        assertNotEquals("unique_complete",read(req=request().replace("\"blocks\":[{\"id\":\"\",\"text\":{\"content\":\" 合成问题🌈 \"}}]","\"blocks\":[3]")).selectionStatus)
        val broken=events().map { if(it["mask"]=="message.status") it+("message" to mapOf("id" to "reply","status" to 2,"error" to mapOf("code" to "internal"))) else it }
        assertEquals("unique_failed",read(stream(broken)).selectionStatus)
    }
}
