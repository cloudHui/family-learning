const { createApp, nextTick } = Vue;

createApp({
  data() {
    return {
      loading:false,error:'',toast:'',toastTimer:null,token:localStorage.getItem('learningToken')||'',
      nameInput:'',passwordInput:'123456',student:null,view:'home',heartbeatTimer:null,
      dashboard:{},statsData:{},showPassword:false,passwordForm:{oldPassword:'',newPassword:'',confirmPassword:''},
      selectedStage:'幼小衔接',stages:['幼小衔接','一年级','二年级','三年级','四年级','五年级','六年级'],
      chineseMode:'learn',words:[],selectedWord:null,showGuide:true,dictationWord:null,dictationRevealed:false,canvasDrawing:false,canvasLast:null,
      mathConfig:{max:10,count:10,operation:'mixed'},mathQuestions:[],mathIndex:0,mathAnswer:'',mathCorrect:0,mathFeedback:null,mathLocked:false,mathStartedAt:null,mathFinished:false,
      mistakeList:[],mistakeSubject:'',recordList:[],resourceList:[],resourceSubject:'',
      printConfig:{max:10,count:20,wordProblems:5,operation:'mixed',showAnswers:false},printQuestions:[],
      subjectName:'',subjectItems:[],
      resourceFolders:[{id:'',name:'全部',icon:'🗂️'},{id:'chinese',name:'语文',icon:'📕'},{id:'math',name:'数学',icon:'📘'},{id:'english',name:'英语',icon:'📗'},{id:'history',name:'历史',icon:'📙'},{id:'chemistry',name:'化学',icon:'🧪'},{id:'picture-books',name:'绘本',icon:'🖼️'},{id:'poems',name:'古诗',icon:'📜'},{id:'worksheets',name:'练习题',icon:'✏️'}]
    };
  },
  computed:{
    greeting(){const h=new Date().getHours();return h<11?'早上好':h<14?'中午好':h<18?'下午好':'晚上好';},
    currentMath(){return this.mathQuestions[this.mathIndex]||{};},
    currentFeature(){if(this.view==='chinese')return this.chineseMode==='dictation'?'语文听写':'识字手写';if(this.view==='math')return this.mathQuestions.length&&!this.mathFinished?'算术答题':'数学区';if(this.view==='print')return'题目打印';return'';}
  },
  async mounted(){
    this.heartbeatBusy=false;
    if(this.token)try{this.student=await this.api('auth/me');this.afterLogin();}catch(_){this.clearSession();}
    window.addEventListener('error',()=>{if(this.token)this.api('auth/frontend-error',{method:'POST'}).catch(()=>{});});
  },
  beforeUnmount(){clearInterval(this.heartbeatTimer);},
  methods:{
    async api(path,options={}){
      const headers={...(options.headers||{})};if(this.token)headers['X-Session-Token']=this.token;
      if(options.body&&!(options.body instanceof FormData))headers['Content-Type']='application/json';
      const response=await fetch('api/'+path,{...options,headers});
      if(!response.ok){let message='请求失败，请稍后再试';try{message=(await response.json()).message||message;}catch(_){}if(response.status===401&&path!=='auth/login')this.clearSession();throw new Error(message);}
      const type=response.headers.get('content-type')||'';return type.includes('json')?response.json():response.text();
    },
    async enterStudent(){
      if(!this.nameInput||!this.passwordInput){this.error='请输入用户名和密码';return;}this.loading=true;this.error='';
      try{const result=await this.api('auth/login',{method:'POST',body:JSON.stringify({username:this.nameInput,password:this.passwordInput,device:this.deviceType()})});this.token=result.token;this.student=result.user;localStorage.setItem('learningToken',this.token);this.afterLogin();}
      catch(error){this.error=error.message;}finally{this.loading=false;}
    },
    afterLogin(){this.selectedStage=this.student.stage||'幼小衔接';this.view='home';if(this.student.mustChangePassword){this.passwordForm={oldPassword:'123456',newPassword:'',confirmPassword:''};this.showPassword=true;}else this.loadDashboard();clearInterval(this.heartbeatTimer);this.sendHeartbeat();this.heartbeatTimer=setInterval(()=>this.sendHeartbeat(),10000);},
    async leaveStudent(){try{await this.api('auth/logout',{method:'POST'});}catch(_){}this.clearSession();},
    clearSession(){clearInterval(this.heartbeatTimer);this.token='';this.student=null;this.view='home';localStorage.removeItem('learningToken');},
    async sendHeartbeat(){if(!this.student||this.heartbeatBusy)return;this.heartbeatBusy=true;try{await this.api('auth/heartbeat',{method:'POST',body:JSON.stringify({page:this.pageName(),feature:this.currentFeature,device:this.deviceType()})});}catch(_){}finally{this.heartbeatBusy=false;}},
    deviceType(){const ua=navigator.userAgent;return /iPad|Tablet/i.test(ua)?'平板':/Mobile|Android|iPhone/i.test(ua)?'手机':'电脑';},
    pageName(){return {home:'首页',chinese:'语文区',math:'数学区',mistakes:'错题库',records:'学习记录',resources:'资源中心',stats:'学习统计',print:'题目打印',stages:'小学阶段',subject:this.subjectName+'区'}[this.view]||this.view;},
    hasPerm(permission){return this.student&&('ADMIN'===this.student.role||(this.student.permissions||[]).includes(permission));},
    openAdmin(){window.location.href='admin.html';},
    async changePassword(){if(this.passwordForm.newPassword!==this.passwordForm.confirmPassword){this.showToast('两次输入的新密码不一致');return;}try{await this.api('auth/password',{method:'POST',body:JSON.stringify(this.passwordForm)});this.student.mustChangePassword=false;this.showPassword=false;this.passwordForm={oldPassword:'',newPassword:'',confirmPassword:''};await this.loadDashboard();this.showToast('密码已修改');}catch(error){this.showToast(error.message);}},
    async loadDashboard(){try{this.statsData=await this.api('stats');this.dashboard=this.statsData.today||{};}catch(error){this.dashboard={};if(this.hasPerm('STATS'))this.showToast(error.message);}},
    goHome(){this.view='home';this.mathQuestions=[];this.dictationWord=null;this.loadDashboard();window.scrollTo(0,0);this.sendHeartbeat();},
    async openView(name){this.view=name;window.scrollTo(0,0);if(name==='chinese')await this.loadWords();if(name==='mistakes')await this.loadMistakes();if(name==='records')await this.loadRecords();if(name==='resources')await this.loadResources();if(name==='stats')await this.loadStats();if(name==='print'&&!this.printQuestions.length)await this.generatePrintable();this.sendHeartbeat();},
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
    async loadResources(){try{this.resourceList=await this.api('resources'+(this.resourceSubject?'?subject='+encodeURIComponent(this.resourceSubject):''));}catch(error){this.showToast(error.message);}},
    async openResource(path){try{const response=await fetch('api/resources/file?path='+encodeURIComponent(path),{headers:{'X-Session-Token':this.token}});if(!response.ok)throw new Error('资源打开失败');const blob=await response.blob();window.open(URL.createObjectURL(blob),'_blank');}catch(error){this.showToast(error.message);}},
    async generatePrintable(){try{this.printQuestions=await this.api(`math/printable?max=${this.printConfig.max}&count=${this.printConfig.count}&operation=${this.printConfig.operation}&wordProblems=${this.printConfig.wordProblems}&stage=${encodeURIComponent(this.selectedStage)}`);}catch(error){this.showToast(error.message);}},
    printWorksheet(){window.print();},
    fileIcon(path){const e=path.split('.').pop().toLowerCase();return e==='pdf'?'📕':['mp3','wav','ogg'].includes(e)?'🎵':['png','jpg','jpeg','gif','webp'].includes(e)?'🖼️':e==='mp4'?'🎬':'📄';},
    formatSize(size){return size<1024?size+' B':size<1048576?(size/1024).toFixed(1)+' KB':(size/1048576).toFixed(1)+' MB';},
    formatDate(value){return value?new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}):'';},
    formatDuration(seconds){return seconds<60?seconds+'秒':seconds<3600?Math.round(seconds/60)+'分钟':(seconds/3600).toFixed(1)+'小时';}
  }
}).mount('#app');
