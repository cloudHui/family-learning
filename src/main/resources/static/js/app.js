const { createApp, nextTick } = Vue;

createApp({
  data() {
    return {
      loading:false,error:'',toast:'',toastTimer:null,token:localStorage.getItem('learningToken')||'',
      authMode:'login',displayName:'',confirmPassword:'',inviteToken:'',regOptions:{openRegister:true,idleMinutes:10},
      nameInput:'',passwordInput:'',student:null,view:'home',heartbeatTimer:null,idleTimer:null,idleMs:10*60*1000,
      dashboard:{},statsData:{},showPassword:false,passwordForm:{oldPassword:'',newPassword:'',confirmPassword:''},
      selectedStage:'幼小衔接',stages:['幼小衔接','一年级','二年级','三年级','四年级','五年级','六年级'],
      chineseMode:'learn',words:[],selectedWord:null,showGuide:true,dictationWord:null,dictationRevealed:false,canvasDrawing:false,canvasLast:null,
      mathConfig:{max:10,count:10,operation:'mixed'},mathQuestions:[],mathIndex:0,mathAnswer:'',mathCorrect:0,mathFeedback:null,mathLocked:false,mathStartedAt:null,mathFinished:false,
      mistakeList:[],mistakeSubject:'',recordList:[],resourceList:[],resourceSubject:'english',
      libraryType:'english',libraryQuery:'',libraryItems:[],libraryLoading:false,libraryTip:'',libraryStatusTip:'',
      textbookMode:'browse',textbookPrefix:'',textbookFolders:[],
      strokeData:null,poetryDetail:null,mediaUrls:{},preview:null,englishAudio:null,
      printConfig:{max:10,count:20,wordProblems:5,operation:'mixed',showAnswers:false},printQuestions:[],
      subjectName:'',subjectItems:[],
      libraryTypes:[
        {id:'english',name:'英语听说',icon:'🎧'},
        {id:'character',name:'汉字笔顺',icon:'✍️'},
        {id:'poetry',name:'古诗词',icon:'📜'},
        {id:'dictionary',name:'英汉词典',icon:'🔤'},
        {id:'textbooks',name:'教材目录',icon:'📚'}
      ],
      resourceFolders:[
        {id:'english',name:'英语文件',icon:'📗'},
        {id:'chinese',name:'语文文件',icon:'📕'},
        {id:'math',name:'数学文件',icon:'📘'},
        {id:'history',name:'历史文件',icon:'📙'},
        {id:'chemistry',name:'化学文件',icon:'🧪'},
        {id:'picture-books',name:'绘本',icon:'🖼️'},
        {id:'worksheets',name:'练习题',icon:'✏️'},
        {id:'',name:'全部上传',icon:'🗂️'}
      ]
    };
  },
  computed:{
    greeting(){const h=new Date().getHours();return h<11?'早上好':h<14?'中午好':h<18?'下午好':'晚上好';},
    currentMath(){return this.mathQuestions[this.mathIndex]||{};},
    currentFeature(){if(this.view==='chinese')return this.chineseMode==='dictation'?'语文听写':'识字手写';if(this.view==='math')return this.mathQuestions.length&&!this.mathFinished?'算术答题':'数学区';if(this.view==='print')return'题目打印';return'';},
    libraryPlaceholder(){
      return {textbooks:'输入年级、科目或书名，如：数学',character:'输入一个汉字，如：学',dictionary:'输入英文单词，如：apple',poetry:'输入篇名或作者，如：静夜思'}[this.libraryType]||'输入查询内容';
    },
    libraryHint(){
      return {textbooks:'可按目录浏览或搜索书名；只打开外部教材链接，不下载 PDF。',character:'输入一个汉字，查看笔顺动画并去语文区练写。',dictionary:'数据来自本地英汉词典。',poetry:'搜索篇名或作者，打开阅读卡可朗读并记学习。',english:'儿童英语图卡：点卡片看图听音。'}[this.libraryType]||'';
    },
    libraryIcon(){return {textbooks:'📚',character:'✍️',dictionary:'🔤',poetry:'📜',english:'🎧'}[this.libraryType]||'📄';},
    visibleLibraryTypes(){
      return this.libraryTypes.filter(item=>{
        if(item.id==='english'||item.id==='dictionary')return this.hasPerm('ENGLISH');
        if(item.id==='character'||item.id==='poetry')return this.hasPerm('CHINESE');
        return this.hasPerm('RESOURCES');
      });
    }
  },
  async mounted(){
    this.heartbeatBusy=false;
    this.onUserActivity=()=>this.bumpIdle();
    ['pointerdown','keydown','touchstart','scroll'].forEach(evt=>window.addEventListener(evt,this.onUserActivity,{passive:true}));
    const params=new URLSearchParams(location.search);
    this.inviteToken=params.get('invite')||'';
    if(this.inviteToken)this.authMode='register';
    await this.loadRegistrationOptions();
    if(this.token)try{this.student=await this.api('auth/me');this.afterLogin();}catch(_){this.clearSession();}
    window.addEventListener('error',()=>{if(this.token)this.api('auth/frontend-error',{method:'POST'}).catch(()=>{});});
  },
  beforeUnmount(){
    clearInterval(this.heartbeatTimer);
    clearTimeout(this.idleTimer);
    ['pointerdown','keydown','touchstart','scroll'].forEach(evt=>window.removeEventListener(evt,this.onUserActivity));
    this.revokeMediaUrls();
    if(this.englishAudio){this.englishAudio.pause();this.englishAudio=null;}
  },
  methods:{
    async api(path,options={}){
      const headers={...(options.headers||{})};if(this.token)headers['X-Session-Token']=this.token;
      if(options.body&&!(options.body instanceof FormData))headers['Content-Type']='application/json';
      const response=await fetch('api/'+path,{...options,headers});
      if(!response.ok){let message='请求失败，请稍后再试';try{message=(await response.json()).message||message;}catch(_){}if(response.status===401&&path!=='auth/login'&&path!=='auth/register'&&!String(path).startsWith('auth/registration'))this.clearSession();throw new Error(message);}
      if(this.token&&path!=='auth/heartbeat')this.bumpIdle();
      const type=response.headers.get('content-type')||'';return type.includes('json')?response.json():response.text();
    },
    async loadRegistrationOptions(){
      try{
        const q=this.inviteToken?('?invite='+encodeURIComponent(this.inviteToken)):'';
        this.regOptions=await this.api('auth/registration'+q);
        this.idleMs=Math.max(1,Number(this.regOptions.idleMinutes||10))*60*1000;
      }catch(_){this.regOptions={openRegister:true,idleMinutes:10};}
    },
    async enterStudent(){
      if(!this.nameInput||!this.passwordInput){this.error='请输入用户名和密码';return;}this.loading=true;this.error='';
      try{const result=await this.api('auth/login',{method:'POST',body:JSON.stringify({username:this.nameInput,password:this.passwordInput,device:this.deviceType()})});this.token=result.token;this.student=result.user;localStorage.setItem('learningToken',this.token);this.afterLogin();}
      catch(error){this.error=error.message;}finally{this.loading=false;}
    },
    async registerStudent(){
      if(!this.nameInput||!this.passwordInput){this.error='请输入用户名和密码';return;}
      if(this.passwordInput.length<6){this.error='密码至少6位';return;}
      if(this.passwordInput!==this.confirmPassword){this.error='两次输入的密码不一致';return;}
      if(!this.regOptions.openRegister&&!this.inviteToken){this.error='请使用邀请链接注册';return;}
      this.loading=true;this.error='';
      try{
        const result=await this.api('auth/register',{method:'POST',body:JSON.stringify({username:this.nameInput,password:this.passwordInput,name:this.displayName||this.nameInput,invite:this.inviteToken||'',device:this.deviceType()})});
        this.token=result.token;this.student=result.user;localStorage.setItem('learningToken',this.token);this.afterLogin();
      }catch(error){this.error=error.message;}finally{this.loading=false;}
    },
    afterLogin(){
      this.selectedStage=this.student.stage||'幼小衔接';this.view='home';
      if(this.visibleLibraryTypes.length&&!this.visibleLibraryTypes.some(item=>item.id===this.libraryType))this.libraryType=this.visibleLibraryTypes[0].id;
      if(this.student.mustChangePassword){this.passwordForm={oldPassword:'123456',newPassword:'',confirmPassword:''};this.showPassword=true;}else this.loadDashboard();
      clearInterval(this.heartbeatTimer);this.sendHeartbeat();this.heartbeatTimer=setInterval(()=>this.sendHeartbeat(),10000);
      this.bumpIdle();
    },
    bumpIdle(){
      if(!this.student)return;
      clearTimeout(this.idleTimer);
      this.idleTimer=setTimeout(()=>this.idleLogout(),this.idleMs);
    },
    async idleLogout(){
      try{await this.api('auth/logout',{method:'POST'});}catch(_){}
      this.clearSession();
      this.error='已超过10分钟未操作，请重新登录';
      this.authMode='login';
    },
    async leaveStudent(){try{await this.api('auth/logout',{method:'POST'});}catch(_){}this.clearSession();},
    clearSession(){clearInterval(this.heartbeatTimer);clearTimeout(this.idleTimer);this.token='';this.student=null;this.view='home';localStorage.removeItem('learningToken');this.revokeMediaUrls();},
    async sendHeartbeat(){if(!this.student||this.heartbeatBusy)return;this.heartbeatBusy=true;try{await this.api('auth/heartbeat',{method:'POST',body:JSON.stringify({page:this.pageName(),feature:this.currentFeature,device:this.deviceType()})});}catch(_){}finally{this.heartbeatBusy=false;}},
    deviceType(){const ua=navigator.userAgent;return /iPad|Tablet/i.test(ua)?'平板':/Mobile|Android|iPhone/i.test(ua)?'手机':'电脑';},
    pageName(){return {home:'首页',chinese:'语文区',math:'数学区',mistakes:'错题库',records:'学习记录',resources:'资源中心',stats:'学习统计',print:'题目打印',stages:'小学阶段',subject:this.subjectName+'区'}[this.view]||this.view;},
    hasPerm(permission){return this.student&&('ADMIN'===this.student.role||(this.student.permissions||[]).includes(permission));},
    openAdmin(){if(this.student&&this.student.mustChangePassword){this.showToast('请先修改初始密码，再进入管理后台');return;}window.location.href='admin.html';},
    async changePassword(){if(this.passwordForm.newPassword!==this.passwordForm.confirmPassword){this.showToast('两次输入的新密码不一致');return;}try{await this.api('auth/password',{method:'POST',body:JSON.stringify(this.passwordForm)});this.student.mustChangePassword=false;this.showPassword=false;this.passwordForm={oldPassword:'',newPassword:'',confirmPassword:''};await this.loadDashboard();this.showToast('密码已修改');}catch(error){this.showToast(error.message);}},
    async loadDashboard(){try{this.statsData=await this.api('stats');this.dashboard=this.statsData.today||{};}catch(error){this.dashboard={};if(this.hasPerm('STATS'))this.showToast(error.message);}},
    goHome(){this.view='home';this.mathQuestions=[];this.dictationWord=null;this.loadDashboard();window.scrollTo(0,0);this.sendHeartbeat();},
    async openView(name){
      this.view=name;window.scrollTo(0,0);
      if(name==='chinese')await this.loadWords();
      if(name==='mistakes')await this.loadMistakes();
      if(name==='records')await this.loadRecords();
      if(name==='resources'){await this.searchLibrary();await this.loadResources();}
      if(name==='stats')await this.loadStats();
      if(name==='print'&&!this.printQuestions.length)await this.generatePrintable();
      this.sendHeartbeat();
    },
    async openSubject(subject){this.subjectName=subject;this.view='subject';try{this.subjectItems=await this.api('content?subject='+encodeURIComponent(subject));}catch(error){this.showToast(error.message);}this.sendHeartbeat();},
    chooseStage(stage){this.selectedStage=stage;this.showToast('已选择'+stage);this.openView('chinese');},
    showToast(message){this.toast=message;clearTimeout(this.toastTimer);this.toastTimer=setTimeout(()=>this.toast='',2200);},

    async loadWords(){try{this.words=await this.api('words?stage='+encodeURIComponent(this.selectedStage));if(!this.words.length&&this.selectedStage!=='幼小衔接'){this.showToast(this.selectedStage+'暂无字库，先展示幼小衔接');this.words=await this.api('words?stage='+encodeURIComponent('幼小衔接'));}this.selectedWord=this.words[0]||null;if(this.chineseMode==='learn')await nextTick(()=>this.initCanvas());}catch(error){this.showToast(error.message);}},
    async selectWord(word){this.selectedWord=word;await nextTick(()=>this.initCanvas());},
    async setChineseMode(mode){this.chineseMode=mode;this.dictationWord=null;this.dictationRevealed=false;if(mode==='learn')await nextTick(()=>this.initCanvas());this.sendHeartbeat();},
    speak(text){if(!('speechSynthesis'in window)){this.showToast('当前浏览器不支持语音播报');return;}speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(text);u.lang='zh-CN';u.rate=.72;speechSynthesis.speak(u);},
    initCanvas(){const canvas=this.$refs.writingCanvas;if(!canvas)return;const ctx=canvas.getContext('2d');ctx.lineCap='round';ctx.lineJoin='round';ctx.strokeStyle='#29263b';ctx.lineWidth=14;const point=e=>{const r=canvas.getBoundingClientRect();return{x:(e.clientX-r.left)*canvas.width/r.width,y:(e.clientY-r.top)*canvas.height/r.height};};canvas.onpointerdown=e=>{e.preventDefault();canvas.setPointerCapture(e.pointerId);this.canvasDrawing=true;this.canvasLast=point(e);ctx.beginPath();ctx.arc(this.canvasLast.x,this.canvasLast.y,ctx.lineWidth/2,0,Math.PI*2);ctx.fillStyle=ctx.strokeStyle;ctx.fill();};canvas.onpointermove=e=>{if(!this.canvasDrawing)return;e.preventDefault();const p=point(e);ctx.beginPath();ctx.moveTo(this.canvasLast.x,this.canvasLast.y);ctx.lineTo(p.x,p.y);ctx.stroke();this.canvasLast=p;};canvas.onpointerup=canvas.onpointercancel=()=>{this.canvasDrawing=false;this.canvasLast=null;};},
    clearCanvas(){const c=this.$refs.writingCanvas;if(c)c.getContext('2d').clearRect(0,0,c.width,c.height);},
    async recordWord(correct){if(!this.selectedWord)return;try{await this.saveRecord('语文','认字',1,correct?1:0,{wordId:this.selectedWord.id,character:this.selectedWord.character});if(!correct)await this.addMistake({subject:'语文',module:'认字',question:'认读汉字：'+this.selectedWord.character,userAnswer:'不认识',correctAnswer:this.selectedWord.pinyin+'；'+this.selectedWord.words,errorType:'不认识'});this.showToast(correct?'真棒！已经记住了':'已放进复习本');const i=this.words.findIndex(w=>w.id===this.selectedWord.id);if(i<this.words.length-1)this.selectWord(this.words[i+1]);}catch(error){this.showToast(error.message);}},
    async startDictation(){if(!this.words.length)await this.loadWords();this.dictationWord=this.words[Math.floor(Math.random()*this.words.length)];this.dictationRevealed=false;await nextTick(()=>this.initCanvas());this.speak(this.dictationWord.character);},
    async finishDictation(correct){const word=this.dictationWord;try{await this.saveRecord('语文','听写',1,correct?1:0,{wordId:word.id,character:word.character});if(!correct)await this.addMistake({subject:'语文',module:'听写',question:'听写：'+word.pinyin,userAnswer:'书写错误',correctAnswer:word.character,errorType:'书写错误'});this.showToast(correct?'听写正确！':'已加入错题库');this.dictationWord=null;setTimeout(()=>this.startDictation(),300);}catch(error){this.showToast(error.message);}},

    async startMath(){try{this.mathQuestions=await this.api(`math/questions?max=${this.mathConfig.max}&count=${this.mathConfig.count}&operation=${this.mathConfig.operation}`);this.mathIndex=0;this.mathAnswer='';this.mathCorrect=0;this.mathFeedback=null;this.mathFinished=false;this.mathLocked=false;this.mathStartedAt=Date.now();await nextTick(()=>this.$refs.mathInput&&this.$refs.mathInput.focus());this.sendHeartbeat();}catch(error){this.showToast(error.message);}},
    async submitMath(){if(this.mathLocked||this.mathAnswer==='')return;this.mathLocked=true;const q=this.currentMath,a=Number(this.mathAnswer),correct=a===q.answer;if(correct){this.mathCorrect++;this.mathFeedback={correct:true,text:'答对了，真棒！'};}else{this.mathFeedback={correct:false,text:`正确答案是 ${q.answer}`};try{await this.addMistake({subject:'数学',module:`${this.mathConfig.max}以内算术`,question:q.text,userAnswer:String(a),correctAnswer:String(q.answer),errorType:'计算错误'});}catch(error){this.showToast(error.message);}}setTimeout(async()=>{if(this.mathIndex>=this.mathQuestions.length-1){this.mathFinished=true;const seconds=Math.max(1,Math.round((Date.now()-this.mathStartedAt)/1000));try{await this.saveRecord('数学',`${this.mathConfig.max}以内算术`,this.mathQuestions.length,this.mathCorrect,{operation:this.mathConfig.operation},seconds);await this.loadDashboard();}catch(error){this.showToast(error.message);}}else{this.mathIndex++;this.mathAnswer='';this.mathFeedback=null;this.mathLocked=false;await nextTick(()=>this.$refs.mathInput&&this.$refs.mathInput.focus());}},correct?650:1400);},
    saveRecord(subject,module,total,correct,details={},durationSeconds=0){return this.api('records',{method:'POST',body:JSON.stringify({subject,module,stage:this.selectedStage,total,correct,durationSeconds,details})});},
    addMistake(item){return this.api('mistakes',{method:'POST',body:JSON.stringify({stage:this.selectedStage,...item})});},
    async loadMistakes(){try{const q=this.mistakeSubject?'?subject='+encodeURIComponent(this.mistakeSubject):'';this.mistakeList=await this.api('mistakes'+q);}catch(error){this.showToast(error.message);}},
    async reviewMistake(item,correct){try{await this.api(`mistakes/${item.id}/review`,{method:'POST',body:JSON.stringify({correct})});this.showToast(correct?'复习成功':'下次继续加油');await this.loadMistakes();await this.loadDashboard();}catch(error){this.showToast(error.message);}},
    statusClass(status){return status==='已掌握'?'status-mastered':status==='待复习'?'status-pending':'status-learning';},
    async loadRecords(){try{this.recordList=await this.api('records');}catch(error){this.showToast(error.message);}},
    async loadStats(){try{this.statsData=await this.api('stats');this.dashboard=this.statsData.today||{};}catch(error){this.showToast(error.message);}},
    async loadResources(){
      try{
        const q=this.resourceSubject?'?subject='+encodeURIComponent(this.resourceSubject):'';
        this.resourceList=await this.api('resources'+q);
      }catch(error){this.showToast(error.message);}
    },
    async selectLibraryType(type){
      this.libraryType=type;
      this.libraryQuery='';
      this.libraryItems=[];
      this.libraryTip='';
      this.strokeData=null;
      this.poetryDetail=null;
      this.textbookPrefix='';
      this.textbookFolders=[];
      if(type==='textbooks')this.textbookMode='browse';
      await this.refreshLibraryStatus();
      await this.searchLibrary();
    },
    async refreshLibraryStatus(){
      try{
        const status=await this.api('library/status');
        const missing=[];
        if(this.libraryType==='english'&&!status.english)missing.push('儿童英语图卡包');
        if(this.libraryType==='character'&&!status.characters)missing.push('汉字笔顺包');
        if(this.libraryType==='dictionary'&&!status.dictionary)missing.push('英汉词典包');
        if(this.libraryType==='poetry'&&!status.poetry)missing.push('古诗词包');
        if(this.libraryType==='textbooks'&&!status.textbooks)missing.push('教材目录');
        this.libraryStatusTip=missing.length?'尚未就绪：'+missing.join('、')+'。请确认安装时已解压 datasets。':'';
      }catch(_){this.libraryStatusTip='';}
    },
    async searchLibrary(){
      this.libraryLoading=true;
      this.libraryTip='';
      this.strokeData=null;
      this.poetryDetail=null;
      try{
        if(this.libraryType==='english'){
          this.libraryItems=await this.api('library/english'+(this.libraryQuery?('?query='+encodeURIComponent(this.libraryQuery)):''));
          if(!this.libraryItems.length)this.libraryTip='暂无英语听说图卡，请确认 english-kids 已解压到资源目录。';
          await this.prefetchEnglishImages();
          return;
        }
        if(this.libraryType==='textbooks'){
          if(this.textbookMode==='browse'&&!this.libraryQuery){await this.loadTextbookTree();return;}
          this.libraryItems=await this.api('library/textbooks?query='+encodeURIComponent(this.libraryQuery||''));
          if(!this.libraryItems.length)this.libraryTip='没有找到匹配教材。';
          return;
        }
        if(!this.libraryQuery){
          this.libraryItems=[];
          this.libraryTip='请输入内容后再点查询。例如：'+this.libraryPlaceholder.replace(/^输入/,'').replace(/，.*$/,'');
          return;
        }
        const key=this.libraryType==='character'?'value':'query';
        const data=await this.api(`library/${this.libraryType}?${key}=${encodeURIComponent(this.libraryQuery)}`);
        if(this.libraryType==='character'){
          this.strokeData=data;
          this.libraryItems=[data];
          await nextTick(()=>this.playStrokeAnimation());
          return;
        }
        this.libraryItems=Array.isArray(data)?data:[data];
        if(!this.libraryItems.length)this.libraryTip='没有找到匹配内容，换个词试试。';
      }catch(error){
        this.libraryItems=[];
        this.libraryTip=error.message||'查询失败，请稍后再试';
        this.showToast(error.message);
      }finally{
        this.libraryLoading=false;
      }
    },
    async loadTextbookTree(){
      this.libraryLoading=true;this.libraryTip='';
      try{
        const data=await this.api('library/textbooks/tree?prefix='+encodeURIComponent(this.textbookPrefix||''));
        this.textbookFolders=data.folders||[];
        this.libraryItems=data.books||[];
        if(!this.textbookFolders.length&&!this.libraryItems.length)this.libraryTip='这个目录下没有教材。';
      }catch(error){this.libraryTip=error.message;this.showToast(error.message);}
      finally{this.libraryLoading=false;}
    },
    openTextbookFolder(folder){
      this.textbookPrefix=this.textbookPrefix?`${this.textbookPrefix}/${folder}`:folder;
      this.loadTextbookTree();
    },
    textbookUp(){
      if(!this.textbookPrefix)return;
      const parts=this.textbookPrefix.split('/');
      parts.pop();
      this.textbookPrefix=parts.join('/');
      this.loadTextbookTree();
    },
    libraryTitle(item){
      if(item.title)return item.title+(item.author?' · '+item.author:'');
      if(item.word)return item.word;
      return item.path||item.character||'学习资料';
    },
    libraryText(item){
      if(item.paragraphs)return (item.paragraphs||[]).slice(0,2).join(' ');
      if(item.translation)return item.translation;
      if(Array.isArray(item.pinyin))return item.pinyin.join(' ');
      if(item.pinyin)return item.pinyin;
      if(item.definition)return item.definition;
      if(item.path&&item.size!=null)return this.formatSize(item.size);
      return item.author||'';
    },
    openLibrary(item){if(item.url)window.open(item.url,'_blank','noopener');},
    openPoetry(item){this.poetryDetail=item;},
    openCharacterResult(item){this.strokeData=item;nextTick(()=>this.playStrokeAnimation());},
    async showStrokeFor(character){
      if(!character)return;
      this.view='resources';
      this.libraryType='character';
      this.libraryQuery=character;
      this.poetryDetail=null;
      await this.searchLibrary();
    },
    practiceStrokeCharacter(){
      if(!this.strokeData||!this.strokeData.character)return;
      this.selectedStage=this.selectedStage||'幼小衔接';
      this.openView('chinese');
      this.showToast('可在语文区对照“'+this.strokeData.character+'”练写');
    },
    async recordPoetryRead(){
      if(!this.poetryDetail)return;
      try{
        await this.saveRecord('语文','古诗词阅读',1,1,{title:this.poetryDetail.title,author:this.poetryDetail.author});
        this.showToast('已记录一次诗词阅读');
      }catch(error){this.showToast(error.message);}
    },
    playStrokeAnimation(){
      const canvas=this.$refs.strokeCanvas;const data=this.strokeData;
      if(!canvas||!data||!Array.isArray(data.medians))return;
      const ctx=canvas.getContext('2d');
      ctx.clearRect(0,0,canvas.width,canvas.height);
      ctx.lineCap='round';ctx.lineJoin='round';ctx.strokeStyle='#7357e8';ctx.lineWidth=8;
      const all=data.medians.flat();
      let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity;
      all.forEach(p=>{if(!p||p.length<2)return;minX=Math.min(minX,p[0]);minY=Math.min(minY,p[1]);maxX=Math.max(maxX,p[0]);maxY=Math.max(maxY,p[1]);});
      if(!isFinite(minX))return;
      const pad=30;const scale=Math.min((canvas.width-pad*2)/Math.max(1,maxX-minX),(canvas.height-pad*2)/Math.max(1,maxY-minY));
      // Make Me a Hanzi: Y 轴向上；Canvas: Y 轴向下，需翻转
      const map=(x,y)=>({x:pad+(x-minX)*scale,y:canvas.height-pad-(y-minY)*scale});
      let strokeIndex=0,pointIndex=0,drawing=null;
      const step=()=>{
        if(strokeIndex>=data.medians.length)return;
        const stroke=data.medians[strokeIndex]||[];
        if(pointIndex===0){drawing=null;ctx.beginPath();}
        if(pointIndex>=stroke.length){strokeIndex++;pointIndex=0;setTimeout(step,180);return;}
        const p=map(stroke[pointIndex][0],stroke[pointIndex][1]);
        if(!drawing){ctx.moveTo(p.x,p.y);drawing=p;}
        else{ctx.lineTo(p.x,p.y);ctx.stroke();ctx.beginPath();ctx.moveTo(p.x,p.y);}
        pointIndex++;
        setTimeout(step,16);
      };
      step();
    },
    async prefetchEnglishImages(){
      for(const item of this.libraryItems){
        if(!item.imagePath)continue;
        try{await this.ensureMediaUrl(item.imagePath);}catch(_){/* 单卡预取失败不阻断列表 */}
      }
    },
    async ensureMediaUrl(path){
      if(!path)return '';
      if(this.mediaUrls[path])return this.mediaUrls[path];
      const response=await fetch('api/resources/file?path='+encodeURIComponent(path),{headers:{'X-Session-Token':this.token}});
      if(!response.ok){
        let message='资源打开失败';
        try{message=(await response.json()).message||message;}catch(_){}
        if(response.status===401)this.clearSession();
        throw new Error(message);
      }
      this.bumpIdle();
      const url=URL.createObjectURL(await response.blob());
      this.mediaUrls={...this.mediaUrls,[path]:url};
      return url;
    },
    async playEnglishCard(item){
      try{
        if(item.imagePath)await this.ensureMediaUrl(item.imagePath);
        if(item.audioPath){
          const url=await this.ensureMediaUrl(item.audioPath);
          if(this.englishAudio){this.englishAudio.pause();}
          this.englishAudio=new Audio(url);
          this.englishAudio.play().catch(()=>this.showToast('音频播放被浏览器拦截，请再点一次'));
        }
        await this.saveRecord('英语','听说图卡',1,1,{word:item.word});
      }catch(error){this.showToast(error.message);}
    },
    revokeMediaUrls(){
      Object.values(this.mediaUrls||{}).forEach(url=>{try{URL.revokeObjectURL(url);}catch(_){}});
      this.mediaUrls={};
      if(this.preview&&this.preview.url){try{URL.revokeObjectURL(this.preview.url);}catch(_){}}
      this.preview=null;
    },
    async previewResource(path){
      try{
        const ext=(path.split('.').pop()||'').toLowerCase();
        const kind=['png','jpg','jpeg','gif','webp','svg'].includes(ext)?'image':['mp3','wav','ogg'].includes(ext)?'audio':ext==='mp4'?'video':'file';
        if(kind==='file'){await this.openResource(path);return;}
        const url=await this.ensureMediaUrl(path);
        this.preview={name:path.split('/').pop(),kind,url};
      }catch(error){this.showToast(error.message);}
    },
    closePreview(){this.preview=null;},
    async openResource(path){try{const url=await this.ensureMediaUrl(path);window.open(url,'_blank');}catch(error){this.showToast(error.message);}},
    async generatePrintable(){try{this.printQuestions=await this.api(`math/printable?max=${this.printConfig.max}&count=${this.printConfig.count}&operation=${this.printConfig.operation}&wordProblems=${this.printConfig.wordProblems}&stage=${encodeURIComponent(this.selectedStage)}`);}catch(error){this.showToast(error.message);}},
    printWorksheet(){window.print();},
    fileIcon(path){const e=path.split('.').pop().toLowerCase();return e==='pdf'?'📕':['mp3','wav','ogg'].includes(e)?'🎵':['png','jpg','jpeg','gif','webp'].includes(e)?'🖼️':e==='mp4'?'🎬':'📄';},
    formatSize(size){return size<1024?size+' B':size<1048576?(size/1024).toFixed(1)+' KB':(size/1048576).toFixed(1)+' MB';},
    formatDate(value){return value?new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}):'';},
    formatDuration(seconds){return seconds<60?seconds+'秒':seconds<3600?Math.round(seconds/60)+'分钟':(seconds/3600).toFixed(1)+'小时';}
  }
}).mount('#app');
