#!/usr/bin/env node
'use strict';

const https = require('https');
if (!process.env.BASE_URL) throw new Error('请通过 BASE_URL 指定已部署服务地址');
const base = new URL(process.env.BASE_URL);
const agent = new https.Agent({rejectUnauthorized: process.env.ALLOW_SELF_SIGNED !== '1'});
let adminToken = '', userToken = '', userId = '', wordId = '', contentId = '', templateId = '', resourcePath = '';

function request(path, options = {}) {
  const payload = options.raw || (options.body === undefined ? null : Buffer.from(JSON.stringify(options.body)));
  const headers = Object.assign({}, options.headers || {});
  if (options.token) headers['X-Session-Token'] = options.token;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (payload) headers['Content-Length'] = payload.length;
  return new Promise((resolve, reject) => {
    const req = https.request(new URL(path, base), {method: options.method || 'GET', headers, agent}, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let data = text;
        try { data = text ? JSON.parse(text) : {}; } catch (_) {}
        if (response.statusCode < 200 || response.statusCode >= 300) return reject(new Error(`${path}: HTTP ${response.statusCode} ${data.message || text}`));
        resolve({status: response.statusCode, data, headers: response.headers});
      });
    });
    req.on('error', reject);
    req.setTimeout(10000, () => req.destroy(new Error(`${path}: timeout`)));
    if (payload) req.write(payload);
    req.end();
  });
}

function check(condition, name) {
  if (!condition) throw new Error(`验收失败：${name}`);
  console.log(`✓ ${name}`);
}

async function main() {
  const unique = `accept${Date.now().toString().slice(-9)}`;
  try {
    check((await request('')).data.includes('成长小课堂'), '学习首页可访问');
    check((await request('admin.html')).data.includes('管理后台'), '管理后台页面可访问');
    check((await request('api/health').catch(error=>({status:error.message.includes('HTTP 401')?401:0}))).status===401, '未登录健康接口拒绝访问');

    const admin = await request('api/auth/login', {method:'POST', body:{username:'admin', password:process.env.ADMIN_PASSWORD || '123456', device:'实机验收'}});
    adminToken = admin.data.token;
    if (admin.data.user.mustChangePassword) throw new Error('管理员必须先在网页中修改初始密码，再执行完整实机验收');
    check((await request('api/health', {token:adminToken})).data.status === 'ok', '登录后健康接口可用');
    const user = await request('api/auth/register', {method:'POST', body:{username:unique, password:'smoke789', name:unique, invite:'', device:'实机验收'}});
    userToken = user.data.token; userId = user.data.user.id;
    check(user.data.user.username === unique, '开放注册建档并进入');
    check((await request('api/auth/me', {token:userToken})).data.id === userId, '会话身份识别');

    const words = (await request('api/words?stage='+encodeURIComponent('幼小衔接'), {token:userToken})).data;
    check(words.length >= 30, '分阶段 30 字识字库');
    check((await request('api/math/questions?max=10&count=5', {token:userToken})).data.length === 5, '10 以内随机加减法');
    const printable = (await request('api/math/printable?max=10&count=4&wordProblems=2&stage='+encodeURIComponent('幼小衔接'), {token:userToken})).data;
    check(printable.length === 6 && printable.some(x => x.type === '文字题'), '算术与文字题打印数据');

    const record = (await request('api/records', {method:'POST', token:userToken, body:{subject:'数学',module:'10以内算术',stage:'幼小衔接',total:5,correct:4,durationSeconds:25}})).data;
    const mistake = (await request('api/mistakes', {method:'POST', token:userToken, body:{subject:'数学',module:'10以内算术',question:'2 + 3 = ?',userAnswer:'4',correctAnswer:'5'}})).data;
    let reviewed;
    for (let i=0;i<3;i++) reviewed=(await request(`api/mistakes/${mistake.id}/review`, {method:'POST', token:userToken, body:{correct:true}})).data;
    check(reviewed.status === '已掌握', '错题分阶段复习至已掌握');
    await request('api/auth/heartbeat', {method:'POST', token:userToken, body:{page:'数学区',feature:'算术答题',device:'实机验收'}});
    const personal = (await request('api/stats', {token:userToken})).data;
    check(personal.today.completed >= 5 && personal.math.total >= 5, '个人今日/数学/趋势统计');

    const users = (await request('api/admin/users', {token:adminToken})).data;
    check(users.some(x => x.id === userId), '管理员用户列表');
    check((await request('api/admin/online', {token:adminToken})).data.some(x => x.userId === userId), '在线页面与功能追踪');
    check((await request(`api/admin/stats/${userId}`, {token:adminToken})).data.today.completed >= 5, '管理员查看单个用户统计');
    check((await request('api/admin/stats', {token:adminToken})).data.loginCount >= 2, '管理员使用与学习汇总统计');

    wordId=(await request('api/admin/words', {method:'POST',token:adminToken,body:{stage:'幼小衔接',character:'验',pinyin:'yàn',words:'验收'}})).data.id;
    contentId=(await request('api/admin/content', {method:'POST',token:adminToken,body:{subject:'英语',stage:'一年级',type:'知识卡片',title:'Live test',body:'Hello',enabled:true}})).data.id;
    templateId=(await request('api/admin/templates', {method:'POST',token:adminToken,body:{stage:'幼小衔接',operation:'add',maxNumber:10,template:'有{a}个，加{b}个，共几个？',enabled:true}})).data.id;
    check(wordId && contentId && templateId, '管理员教学数据新增');
    await request(`api/admin/records/${userId}/${record.id}`, {method:'PUT',token:adminToken,body:{subject:'数学',module:'验收编辑',stage:'幼小衔接',total:5,correct:5,durationSeconds:25}});
    await request(`api/admin/mistakes/${userId}/${mistake.id}`, {method:'PUT',token:adminToken,body:{subject:'数学',module:'验收编辑',question:'2 + 3 = ?',correctAnswer:'5',status:'已掌握'}});
    check(true, '管理员记录和错题编辑');

    const boundary='----familyLearningSmoke'; const file=Buffer.from('live smoke resource','utf8');
    const multipart=Buffer.concat([Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="live-smoke.txt"\r\nContent-Type: text/plain\r\n\r\n`),file,Buffer.from(`\r\n--${boundary}--\r\n`)]);
    const uploaded=(await request('api/resources?subject=worksheets',{method:'POST',token:adminToken,raw:multipart,headers:{'Content-Type':`multipart/form-data; boundary=${boundary}`}})).data;
    resourcePath=uploaded.path;
    check((await request('api/resources', {token:userToken})).data.some(x=>x.path===resourcePath), '资源上传与普通用户浏览');
    check((await request('api/admin/report/preview', {token:adminToken})).data.content.includes('成长小课堂'), '每日邮件内容生成');
    if (process.env.SEND_REPORT === '1') {
      const sent=(await request('api/admin/report/send',{method:'POST',token:adminToken})).data;
      check(sent.status === 'sent', '每日统计邮件真实投递');
    }

    await request('api/auth/password',{method:'POST',token:userToken,body:{oldPassword:'smoke789',newPassword:'654321'}});
    await request(`api/admin/users/${userId}/reset-password`,{method:'POST',token:adminToken});
    const resetLogin=await request('api/auth/login',{method:'POST',body:{username:unique,password:'123456',device:'实机验收'}});
    check(resetLogin.data.token&&resetLogin.data.user.mustChangePassword, '修改密码、管理员重置与再次强制改密');
  } finally {
    const cleanup = async (path, method='DELETE') => { try { await request(path,{method,token:adminToken}); } catch (_) {} };
    if(resourcePath)await cleanup('api/resources?path='+encodeURIComponent(resourcePath));
    if(wordId)await cleanup(`api/admin/words/${wordId}`);
    if(contentId)await cleanup(`api/admin/content/${contentId}`);
    if(templateId)await cleanup(`api/admin/templates/${templateId}`);
    if(userId)await cleanup(`api/admin/users/${userId}`);
  }
  console.log('实机功能验收全部通过，临时数据已清理。');
}

main().catch(error => {console.error(error.stack || error.message);process.exitCode=1;});
