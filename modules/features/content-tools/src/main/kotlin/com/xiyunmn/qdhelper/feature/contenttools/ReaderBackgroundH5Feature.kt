package com.xiyunmn.qdhelper.feature.contenttools

import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap

/** The decoration center is deployed independently of the native theme detail Activity. */
object ReaderBackgroundH5Feature : InstallableFeature {
    override val name = "ReaderBackgroundH5Feature"
    private var subscription: AutoCloseable? = null
    private val views = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val polling = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var epoch = 0

    init {
        FeatureResources.register("reader-background-h5") {
            epoch++
            subscription?.close()
            subscription = null
            synchronized(views) { views.keys.toList() }.forEach { webView ->
                (webView as? View)?.post {
                    CardPlazaWebViewAccess.evaluate(webView, script(false)) { }
                }
            }
            views.clear()
            polling.clear()
        }
    }

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (!HostSymbols.isMainProcess(processName)) return
        val fragment = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_BROWSER_FRAGMENT, classLoader)
        val finished = fragment?.let { type ->
            FeatureHooks.allDeclaredMethods(type).singleOrNull {
                it.name == "onPageFinished" && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        }?.let { method ->
            FeatureHooks.hookAfter(module, method, "reader.background.h5:page finished", "qdhelper.reader.background.h5.finished") { chain, _ ->
                apply(chain.thisObject)
            }
        } ?: false
        synchronized(this) {
            if (subscription == null) {
                subscription = ActivityLifecycleCallbacks.subscribe(
                    owner = OwnerId("content-tools"),
                    id = "qdhelper.reader.background.h5.lifecycle",
                    resumed = { activity ->
                        if (activity.javaClass.name == HostSymbols.Classes.QD_BROWSER_ACTIVITY) {
                            activity.window?.decorView?.postDelayed({ apply(activity) }, 500L)
                        }
                    },
                    windowFocusChanged = { activity, focused ->
                        if (focused && activity.javaClass.name == HostSymbols.Classes.QD_BROWSER_ACTIVITY) {
                            activity.window?.decorView?.post { apply(activity) }
                        }
                    },
                )
            }
        }
        FeatureHooks.i("Reader background H5 installed: pageFinished=$finished, lifecycle=${subscription != null}, phase=$phase")
    }

    private fun apply(owner: Any?) {
        owner ?: return
        CardPlazaWebViewAccess.webViewsOf(owner).forEach { webView ->
            if (!supported(CardPlazaWebViewAccess.urlOf(webView))) return@forEach
            val view = webView as? View ?: return@forEach
            val enabled = SettingsRuntime.isReaderBackgroundLocalUnlockEnabled(view.context)
            if (!enabled && !views.containsKey(webView)) return@forEach
            CardPlazaWebViewAccess.evaluate(webView, script(enabled)) { raw -> report(webView, raw) }
            if (polling.put(webView, true) == true) return@forEach
            val currentEpoch = epoch
            // Hydration and user actions finish after evaluateJavascript returns.
            listOf(1500L, 6000L, 15000L).forEach { delay ->
                view.postDelayed({
                    if (currentEpoch != epoch) return@postDelayed
                    if (supported(CardPlazaWebViewAccess.urlOf(webView))) {
                        val current = SettingsRuntime.isReaderBackgroundLocalUnlockEnabled(view.context)
                        CardPlazaWebViewAccess.evaluate(webView, script(current)) { raw -> report(webView, raw) }
                    }
                    if (delay == 15000L) polling.remove(webView)
                }, delay)
            }
        }
    }

    private fun report(view: Any, raw: String?) {
        val result = raw.orEmpty().take(300)
        if (views.put(view, result) != result) FeatureHooks.i("Reader background H5: $result")
    }

    private fun supported(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host in setOf("h5.if.qidian.com", "brave.if.qidian.com", "jtest.if.qidian.com") &&
            uri.path.trimEnd('/') in setOf("/new/dress-up-center", "/new/dress-up-center/reading-bg-detail")
    }.getOrDefault(false)

    private fun script(enabled: Boolean) = READER_BACKGROUND_H5_SCRIPT.replace("__ENABLED__", enabled.toString())
}

private const val READER_BACKGROUND_H5_SCRIPT = """
(function(){
  var hosts=['h5.if.qidian.com','brave.if.qidian.com','jtest.if.qidian.com'];
  function allowed(){return location.protocol==='https:'&&hosts.indexOf(location.hostname)>=0&&
    ['/new/dress-up-center','/new/dress-up-center/reading-bg-detail'].indexOf(location.pathname.replace(/\/+$/,''))>=0;}
  if(!allowed())return 'ignored';
  var key='__qdhReaderBackgroundH5V1',old=window[key];
  if(old)return old.setEnabled(__ENABLED__);
  var active=false,observer=null,scheduled=false,generation=0,busy=false,uncertain=false,status='idle';
  var records=[],buttonRecord=null,dialog=null,cancelChoice=null,lastApplied='',modulePromise=null;
  function id(value){var s=String(value==null?'':value);return /^[1-9][0-9]{0,18}$/.test(s)?s:'';}
  function instances(callback){
    var root=document.getElementById('app'),app=root&&root.__vue_app__,seen=new Set(),nodes=new Set();
    function visitInstance(i){if(!i||seen.has(i)||seen.size>=400)return;seen.add(i);callback(i);visit(i.subTree);}
    function visit(v){if(!v||nodes.has(v)||nodes.size>=3000)return;nodes.add(v);
      if(Array.isArray(v)){v.forEach(visit);return;}visitInstance(v.component);visit(v.children);visit(v.dynamicChildren);
      if(v.suspense){visit(v.suspense.activeBranch);visit(v.suspense.pendingBranch);}}
    visitInstance(app&&(app._instance||(app._container&&app._container._vnode&&app._container._vnode.component)));
  }
  function selected(){
    var result=null;instances(function(i){if(i.type&&i.type.__name==='apply-bg-dialog'&&i.props&&i.props.pageId==='readbackgrounddetail'){
      result={id:id(i.props.themeId),type:Number(i.props.themeType),name:String(i.props.themeName||'')};}});
    return result&&result.id?result:null;
  }
  function restoreButton(){if(!buttonRecord)return;
    var r=buttonRecord;if(r.style===null)r.original.removeAttribute('style');else r.original.setAttribute('style',r.style);
    r.local.remove();buttonRecord=null;
  }
  function patchCards(){
    records=records.filter(function(r){return !r.instance.isUnmounted&&r.instance.props.item===r.item;});
    instances(function(i){if(!i.type||i.type.__name!=='reading-bg-card')return;
      var item=i.props&&i.props.item;if(!item||!id(item.Id)||Number(item.ThemeType)===104||Number(item.HaveStatus)!==0)return;
      if(!records.some(function(r){return r.item===item;}))records.push({item:item,value:item.HaveStatus,instance:i});
      item.HaveStatus=1;
    });
  }
  function scan(){
    if(!active||!allowed())return;
    patchCards();
    var original=document.querySelector('main.reading-bg-detail button.btn-primary[data-state]'),s=selected();
    var locked=original&&['openMember','obtain','notObtained'].indexOf(original.getAttribute('data-state'))>=0;
    if(!locked||!s||s.type===104){restoreButton();return;}
    if(buttonRecord&&(buttonRecord.original!==original||buttonRecord.id!==s.id||!buttonRecord.local.isConnected))restoreButton();
    if(!buttonRecord){
      var button=original.cloneNode(false);button.removeAttribute('id');button.removeAttribute('data-state');button.removeAttribute('disabled');
      button.setAttribute('data-qdh-local-theme','1');button.type='button';
      buttonRecord={original:original,local:button,id:s.id,style:original.getAttribute('style')};
      original.style.setProperty('display','none','important');original.after(button);
      button.addEventListener('click',function(event){event.preventDefault();event.stopPropagation();run();});
    }
    var label=busy?'处理中…':uncertain?'结果待确认，请重新进入':lastApplied===s.id?'重新应用':'使用';
    if(buttonRecord.local.textContent!==label)buttonRecord.local.textContent=label;
    if(buttonRecord.local.disabled!==(busy||uncertain))buttonRecord.local.disabled=busy||uncertain;
  }
  function schedule(){if(scheduled||!active)return;scheduled=true;requestAnimationFrame(function(){scheduled=false;scan();});}
  function trusted(raw){try{var u=new URL(raw,location.href);return u.protocol==='https:'&&u.hostname==='qdfepccdn.qidian.com'&&
    hosts.some(function(h){return u.pathname.indexOf('/'+h+'/new/js/')===0;})?u:null;}catch(e){return null;}}
  function commonUrl(){
    var entries=Array.from(document.querySelectorAll('script[type="module"][src]')).map(function(s){return trusted(s.src);}).filter(Boolean);
    var entry=entries.find(function(u){return /^index_virtual_.*_entry\.js$/.test(u.pathname.split('/').pop());});
    if(!entry)throw Error('entry_missing');var prefix=entry.pathname.replace(/[^/]+$/,'');
    var urls=Array.from(document.querySelectorAll('script[src],link[href]')).map(function(n){return n.src||n.href;})
      .concat(performance.getEntriesByType('resource').map(function(r){return r.name;}));
    var found=Array.from(new Set(urls.map(trusted).filter(function(u){return u&&u.pathname.indexOf(prefix)===0&&
      /^page_common_dress-up-center_[^/]+_chunk\.js$/.test(u.pathname.split('/').pop());}).map(function(u){return u.href;})));
    if(found.length!==1)throw Error('common_ambiguous');return found[0];
  }
  function timeout(p,ms,reason){return new Promise(function(resolve,reject){var timer=setTimeout(function(){reject(Error(reason));},ms);
    Promise.resolve(p).then(function(v){clearTimeout(timer);resolve(v);},function(e){clearTimeout(timer);reject(e);});});}
  async function detail(themeId){
    if(!modulePromise)modulePromise=timeout(import(commonUrl()),8000,'module_timeout').catch(function(e){modulePromise=null;throw e;});
    var m=await modulePromise,fs=Object.keys(m).map(function(k){return m[k];}).filter(function(f){return typeof f==='function'&&
      String(f).indexOf('/argus/api/v1/theme/getdetail')>=0;});
    if(fs.length!==1)throw Error('detail_api_missing');
    var data=await timeout(fs[0](themeId),12000,'detail_timeout');
    if(!data||String(data.Id||data.ThemeId)!==themeId)throw Error('detail_mismatch');
    return data;
  }
  function sdk(name,args,progress){
    return timeout(new Promise(function(resolve,reject){
      var app=window.jssdk&&window.jssdk.app;if(!app||typeof app[name]!=='function'){reject(Error('bridge_missing'));return;}
      app[name](args,function(r){if(!r||typeof r!=='object'){reject(Error('bridge_response'));return;}
        if(name==='downloadTheme'&&String(r.status)!=='4'){
          if(String(r.status)==='3'&&progress)progress(r.progress);return;
        }resolve(r);
      });
    }),name==='downloadTheme'?180000:15000,name+'_unknown');
  }
  function toast(text){var node=document.createElement('div');node.textContent=text;
    node.style.cssText='position:fixed;z-index:2147483647;left:50%;bottom:24%;transform:translateX(-50%);max-width:80%;padding:12px 18px;border-radius:12px;background:#333;color:#fff;font-size:14px;text-align:center';
    document.body.appendChild(node);setTimeout(function(){node.remove();},3500);
  }
  function choose(bookId,name){
    if(!bookId)return Promise.resolve(0);
    return new Promise(function(resolve){
      var overlay=document.createElement('div');dialog=overlay;overlay.setAttribute('role','dialog');overlay.setAttribute('aria-modal','true');
      overlay.style.cssText='position:fixed;inset:0;z-index:2147483646;background:#0008;display:flex;align-items:flex-end;justify-content:center';
      var panel=document.createElement('div');panel.style.cssText='width:100%;max-width:560px;box-sizing:border-box;padding:24px 20px calc(20px + env(safe-area-inset-bottom));border-radius:20px 20px 0 0;background:var(--surface-bw-white,#fff);color:var(--surface-gray-900,#222)';
      var title=document.createElement('div');title.textContent=name||'应用阅读背景';title.style.cssText='font-size:18px;text-align:center;margin-bottom:16px';panel.appendChild(title);
      function finish(v){overlay.remove();dialog=null;cancelChoice=null;resolve(v);}cancelChoice=function(){finish(null);};
      [['应用于全局',0],['仅应用于本书',1],['取消',null]].forEach(function(option){var b=document.createElement('button');b.type='button';b.textContent=option[0];
        b.style.cssText='display:block;width:100%;padding:14px;margin-top:8px;border:0;border-radius:12px;font:inherit;color:inherit;background:var(--surface-gray-100,#eee)';
        b.onclick=function(){finish(option[1]);};panel.appendChild(b);});
      overlay.appendChild(panel);overlay.onclick=function(e){if(e.target===overlay)finish(null);};document.body.appendChild(overlay);
    });
  }
  async function run(){
    var s=selected();if(!active||!allowed()||busy||uncertain||!s||s.type===104)return;
    var token=generation,bookId=id(new URL(location.href).searchParams.get('bookId'));busy=true;status='choosing';scan();
    function valid(){var current=selected();return active&&allowed()&&token===generation&&current&&current.id===s.id;}
    try{
      var single=await choose(bookId,s.name);if(single===null||!valid()){status='cancelled';return;}
      status='loading_detail';var data=await detail(s.id);if(!valid())return;
      var url;try{url=new URL(data.FileUrl);}catch(e){throw Error('file_missing');}
      if(!['https:','http:'].includes(url.protocol)||!data.MD5||Number(data.ThemeType)!==s.type)throw Error('file_contract');
      // Keep native file validation, extraction and persistence; do not synthesize an account entitlement.
      status='downloading';var result=await sdk('downloadTheme',{themeId:s.id,fileUrl:data.FileUrl,md5:String(data.MD5),themeType:String(data.ThemeType)});
      if(!valid())return;if(String(result.isSuccess)!=='1')throw Error('download_failed');
      status='applying';var applied=await sdk('applyTheme',{themeId:s.id,singleBook:single,bookId:single?bookId:''});
      if(!valid())return;if(String(applied.code)!=='0')throw Error('apply_failed');
      lastApplied=s.id;status='applied';toast('已应用阅读背景');
    }catch(e){if(token===generation&&active){
      var code=String(e&&e.message||'error');status=/^[a-z_]+$/.test(code)?code:'request_failed';
      if(status.endsWith('_unknown'))uncertain=true;
      toast(uncertain?'结果未确认，请重新进入背景页查看':'背景应用失败，请稍后重试');
    }}finally{busy=false;scan();}
  }
  function setEnabled(value){
    value=Boolean(value)&&allowed();
    if(value!==active){active=value;generation++;
      if(!active){if(observer)observer.disconnect();observer=null;if(cancelChoice)cancelChoice();restoreButton();
        records.forEach(function(r){if(Number(r.item.HaveStatus)===1)r.item.HaveStatus=r.value;});records=[];
      }else{observer=new MutationObserver(schedule);observer.observe(document.body||document.documentElement,
        {childList:true,subtree:true,attributes:true,attributeFilter:['data-state','data-theme-id']});}
    }
    if(active)scan();return 'enabled='+active+',cards='+records.length+',detail='+Boolean(buttonRecord)+',state='+status;
  }
  window[key]={setEnabled:setEnabled};return setEnabled(__ENABLED__);
})()
"""
