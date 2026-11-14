import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const base = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('observability.js', base), 'utf8');
const plain = v => JSON.parse(JSON.stringify(v));
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return { promise, resolve }; };
function snapshot() { return { config: { schemaVersion: 1, revision: 4, endpoint: 'https://example.test/api/public/otel/v1/traces', publicKey: 'pk-synthetic', sampleRate: 1, captureMode: 'metadata', categories: [], toolSources: [], mailAccounts: [], scope: 'all-runtime', sessionId: '', caCertificatePem: '' }, enabled: false, credentialConfigured: true, persistentCredentialsSupported: true, credentialCleanupPending: false, contentExpiresOnRestart: true, status: { state: 'disabled', accepted: 0, dropped: 0, failed: 0, lastError: '' } }; }
function setup(handler) {
 const calls = []; const context = vm.createContext({ AbortController, t: k => k }); vm.runInContext(source, context);
 return { calls, model: context.createObservabilityModel((...args) => { calls.push(args); return handler?.(...args) ?? Promise.resolve(args[0] === '/preview' ? { destination: { endpoint: snapshot().config.endpoint, publicKey: snapshot().config.publicKey, category: 'public', addresses: ['203.0.113.2'], caFingerprint: 'system-trust' }, confirmationToken: 'synthetic-token' } : snapshot()); }, () => {}), context };
}
test('opening is read-only; strips unsolicited credentials from snapshots', async () => {
 const h = setup(() => Promise.resolve({ ...snapshot(), secret: 'NEVER', credentialRef: 'NEVER' })); await h.model.load();
 assert.deepEqual(h.calls.map(c => c.slice(0, 2)), [['', 'GET']]); assert.doesNotMatch(JSON.stringify(h.model.snapshot), /NEVER/);
 assert.equal(h.model.snapshot.config.captureMode, 'metadata'); assert.equal(h.model.snapshot.enabled, false);
});
test('save sends exact metadata, locks duplicates and never enables', async () => {
 const pending = deferred(); const h = setup((p,m) => m === 'PUT' ? pending.promise : undefined); await h.model.load();
 h.model.change('captureMode','content'); h.model.change('categories',['modelText','mailBody']); h.model.change('mailAccounts',['mail-a']); h.model.setSecret('SECRET');
 const saving = h.model.save(); await h.model.save(); h.model.change('endpoint','https://wrong');
 const body = h.calls.at(-1)[2]; assert.equal(body.expectedRevision,4); assert.doesNotMatch(JSON.stringify(body), /SECRET/);
 assert.equal(h.calls.length,2); const s = snapshot(); s.config = plain(body.config); s.config.revision++; pending.resolve(s); await saving;
 assert.equal(h.model.snapshot.enabled,false); assert.equal(h.model.dirty(),false); assert.equal(h.model.secret,'');
});
test('credential write is separate, write-only, encrypted-store availability required', async () => {
 const h = setup((p) => { if(p === '/credential') { const s = snapshot(); s.config.revision++; return Promise.resolve(s); } }); await h.model.load(); h.model.setSecret('SYNTHETIC'); await h.model.credential();
 assert.equal(h.calls.at(-1)[0],'/credential'); assert.equal(h.calls.at(-1)[2].secret,undefined); assert.equal(h.model.secret,'');
 const n=setup(() => Promise.resolve({...snapshot(),persistentCredentialsSupported:false})); await n.model.load(); n.model.setSecret('SYNTHETIC'); await n.model.credential(); assert.equal(n.calls.length,1);
});
test('every remote action requires explicit confirmation of exact saved recipient and scope', async () => {
 const h=setup((p) => { if(p==='/enable') return Promise.resolve({...snapshot(), enabled:true}); }); await h.model.load();
 for(const action of ['enable','test','disable','disconnect']) await h.model.action(action, () => false); assert.equal(h.calls.length,3); assert.equal(h.calls.filter(c=>c[0]==='/enable'||c[0]==='/test').length,0);
 let details; await h.model.action('enable', value => { details=plain(value); return true; });
 assert.deepEqual(details.config,snapshot().config); assert.deepEqual(details.destination.addresses,['203.0.113.2']); assert.deepEqual(plain(h.calls.at(-1)[2]),{expectedRevision:4,confirmationToken:'synthetic-token',disclosureVersion:1}); assert.equal(h.model.snapshot.enabled,true);
});
test('test is synthetic and does not imply enable', async () => { const h=setup(p => p==='/test' ? Promise.resolve({...snapshot(),testAccepted:true}) : undefined); await h.model.load(); await h.model.action('test',()=>true); assert.equal(h.model.snapshot.enabled,false); assert.equal(h.model.notice,'test'); });
test('dirty edits block enable but revoke preserves them', async () => { const h=setup(); await h.model.load(); h.model.change('publicKey','new-project'); await h.model.action('enable',()=>true); assert.equal(h.calls.length,1); await h.model.action('disable',()=>true); assert.equal(h.model.draft.publicKey,'new-project'); assert.equal(h.model.metadataDirty(),true); });
test('unknown write locks retries until readback; safety revoke remains available', async () => {
 const h=setup((p,m) => m==='PUT' ? Promise.reject({message:'SECRET'}) : undefined); await h.model.load(); h.model.change('publicKey','new'); await h.model.save();
 assert.equal(h.model.unknown,true); assert.doesNotMatch(h.model.error,/SECRET/); await h.model.save(); await h.model.action('enable',()=>true); assert.equal(h.calls.length,2);
 await h.model.action('disable',()=>true); assert.equal(h.calls.at(-1)[0],'/disable'); assert.equal(h.model.unknown,true); await h.model.load(); assert.equal(h.model.unknown,false);
});
test('revision conflict keeps draft and hides raw error',async()=>{ const h=setup((p,m)=>m==='PUT'?Promise.reject({status:409,code:'revision_conflict',message:'SECRET'}):undefined); await h.model.load(); h.model.change('publicKey','draft'); await h.model.save(); assert.equal(h.model.draft.publicKey,'draft'); assert.equal(h.model.error,'observability.error.revision_conflict'); assert.equal(h.model.unknown,false); });
test('malformed or changed-target mutation response is not reported successful', async()=>{ const h=setup(p=>p==='/enable'?Promise.resolve({...snapshot(),enabled:true,config:{...snapshot().config,endpoint:'https://other'}}):undefined); await h.model.load(); await h.model.action('enable',()=>true); assert.equal(h.model.unknown,true); assert.equal(h.model.snapshot.enabled,false); });
test('destroy cancels load and ignores late response', async()=>{ const pending=deferred(); const h=setup(()=>pending.promise); const loading=h.model.load(); const signal=h.calls[0][3]; h.model.destroy(); assert.equal(signal.aborted,true); pending.resolve(snapshot()); await loading; assert.equal(h.model.snapshot,null); });
test('pending credential cleanup stays explicit', async()=>{ const h=setup(p=>p==='/disconnect'?Promise.resolve({...snapshot(),config:{...snapshot().config,revision:5},credentialConfigured:false,credentialCleanupPending:true}):undefined); await h.model.load(); await h.model.action('disconnect',()=>true); assert.equal(h.model.notice,'cleanupPending'); });
test('locale coverage and independent settings wiring',()=>{
 const en=JSON.parse(fs.readFileSync(new URL('locales/en.json',base))); const zh=JSON.parse(fs.readFileSync(new URL('locales/zh.json',base)));
 for(const key of Object.keys(en).filter(k=>k.startsWith('observability.'))) assert.equal(typeof zh[key],'string',key);
 for(const match of source.matchAll(/t\("(observability\.[\w.]+)"/g)) if(!match[1].endsWith('.')) assert.equal(typeof en[match[1]],'string',match[1]);
 const ui=fs.readFileSync(new URL('ui.js',base),'utf8'); assert.match(ui,/mountObservability/); assert.match(ui,/observabilityView\.dirty\(\)/); assert.match(ui,/observabilityView\.saving\(\)/); assert.doesNotMatch(source,/localStorage|sessionStorage|innerHTML/);
});
test('unreadable saved configuration preserves file and blocks all configuration writes',async()=>{ const s=snapshot(); s.status.state='unavailable';s.status.lastError='config_unavailable';const h=setup(()=>Promise.resolve(s));await h.model.load();assert.equal(h.model.snapshot.status.state,'unavailable');assert.equal(h.model.locked(),true);h.model.change('publicKey','overwrite');h.model.setSecret('SECRET');await h.model.save();await h.model.credential();await h.model.action('enable',()=>true);await h.model.action('disconnect',()=>true);assert.equal(h.calls.length,1);assert.equal(h.model.draft.publicKey,'pk-synthetic'); });
test('preview failures never fall back to action or disclose a different recipient',async()=>{
 for(const result of [Promise.reject({code:'unsafe_destination'}),Promise.resolve({destination:{endpoint:'https://other'},confirmationToken:'token'})]) {
  const h=setup(p=>p==='/preview'?result:undefined);await h.model.load();let confirmed=false;await h.model.action('enable',()=>{confirmed=true;return true;});assert.equal(confirmed,false);assert.equal(h.calls.at(-1)[0],'/preview');assert.equal(h.model.busy,'');assert.equal(h.model.unknown,false);
 }
});
test('preview locks duplicate actions and late destroyed responses cannot enable',async()=>{
 const pending=deferred();const h=setup(p=>p==='/preview'?pending.promise:undefined);await h.model.load();const action=h.model.action('enable',()=>{assert.fail('late confirmation');});await h.model.action('enable',()=>true);assert.equal(h.calls.length,2);assert.equal(h.model.busy,'preview');h.model.destroy();pending.resolve({});await action;assert.equal(h.calls.length,2);
});
test('consent discloses earlier context in subsequent calls, without claiming history is never uploaded',()=>{
 const en=JSON.parse(fs.readFileSync(new URL('locales/en.json',base))); const zh=JSON.parse(fs.readFileSync(new URL('locales/zh.json',base)));
 assert.match(en['observability.scope.all-runtime'],/Subsequent calls/); assert.doesNotMatch(en['observability.scope.all-runtime'],/new sessions/i);
 for(const key of ['observability.intro','observability.confirm.enable']) { assert.match(en[key],/earlier conversation context/); assert.match(en[key],/scan or backfill/); assert.doesNotMatch(en[key],/Nothing is uploaded from prior history/); assert.match(zh[key],/先前对话上下文/); assert.match(zh[key],/不会独立扫描或补传历史记录/); }
 assert.match(zh['observability.scope.all-runtime'],/后续调用/);
});
test('grid action keeps intrinsic button height beside multiline CA field',()=>{
 const css=fs.readFileSync(new URL('observability.css',base),'utf8');
 const rule=css.match(/\.observability-grid > \.btn\s*\{([^}]+)\}/);
 assert.ok(rule);assert.match(rule[1],/align-self:\s*start/);assert.match(rule[1],/justify-self:\s*start/);assert.match(rule[1],/max-width:\s*100%/);assert.match(rule[1],/white-space:\s*normal/);
});
