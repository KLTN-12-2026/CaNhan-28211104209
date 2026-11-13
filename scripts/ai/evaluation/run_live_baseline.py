"""Owned local providers + isolated H2 baseline. No pulls, production DB, heldout inference or retries."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import run_evaluation as evaluator
from validate_parser_dataset import require,decode

REPO=evaluator.REPO
FLAGS=getattr(subprocess,'CREATE_NO_WINDOW',0)


def output_path(value):
    p=Path(value).resolve()
    require(p.is_relative_to(REPO/'.runtime') and not p.exists(),'new output below .runtime required')
    require(not any(c in str(p) for c in '&|<>^%!'),'unsafe shell path')
    return p


def free_port(port):
    with socket.socket() as s:
        s.settimeout(1)
        require(s.connect_ex(('127.0.0.1',port))!=0,'provider port already occupied; no takeover')


def routing_module():
    path=REPO/'scripts/coordination/ai3/run-routing-proof.py'
    spec=importlib.util.spec_from_file_location('g25_routing_guard',path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def docker(*args):
    r=subprocess.run(['docker',*args],capture_output=True,text=True,timeout=30,creationflags=FLAGS)
    require(r.returncode==0,'Docker command failed (no auto-create or retry)')
    return r.stdout


def child_environment(java_home):
    env=dict(os.environ)
    for k in list(env):
        if k.startswith(('KDRIVE_','SPRING_','OLLAMA_')) or k.upper() in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY'):
            env.pop(k)
    env.update(JAVA_HOME=str(java_home),AI_EVAL_PYTHON=sys.executable,KDRIVE_EVAL_LIVE='1')
    return env


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True)
    p.add_argument('--java-home',type=Path,required=True)
    p.add_argument('--ollama-exe',type=Path,default=Path('C:/Users/LENOVO/AppData/Local/Programs/Ollama/ollama.exe'))
    p.add_argument('--models',type=Path,default=Path('E:/AI_Cache/ollama'))
    args=p.parse_args()
    out=output_path(args.evidence)
    require((args.java_home/'bin/java.exe').is_file() and args.ollama_exe.is_file(),'JDK/Ollama missing')
    model_manifest=args.models/'manifests/registry.ollama.ai/library/qwen3/8b'
    require(model_manifest.is_file(),'qwen3:8b missing; never auto-download')
    model_data=decode(model_manifest.read_text(encoding='utf-8'))
    for layer in [model_data['config'],*model_data['layers']]:
        digest=layer['digest'];require(digest.startswith('sha256:') and len(digest)==71,'model layer digest')
        blob=args.models/'blobs'/digest.replace(':','-')
        require(blob.is_file() and blob.stat().st_size==layer['size'],'model blob missing/size drift')
    evaluator.load_cases(REPO/'scripts/ai/evaluation/parser-v1',['parser'])
    evaluator.load_cases(REPO/'scripts/ai/evaluation/scenarios-v1',['recommendation','itinerary'])
    free_port(11434);free_port(5000)
    guard=routing_module();entry=guard.osrm_fixture(REPO/'.runtime/docker-fixtures/osrm.json')
    info=json.loads(docker('inspect',entry['id']))[0]
    graph=Path(info['Mounts'][0]['Source']).resolve()
    require(graph.is_relative_to(REPO/'.runtime'),'registered runtime graph required')
    guard.validate_osrm_reuse(entry,info,graph)
    graph_manifest=REPO/entry['evidence']/'graph-manifest.json'
    gm=decode(graph_manifest.read_text(encoding='utf-8-sig'))
    require(gm['image']==guard.IMAGE,'graph image mismatch')
    for name,meta in gm['files'].items():
        source=(graph/name).resolve();require(source.is_relative_to(graph),'graph traversal')
        require(evaluator.digest(source).lower()==meta['sha256'].lower(),'graph hash drift')
    out.mkdir(parents=True)
    env=child_environment(args.java_home)
    source_files=[*list((REPO/'backend/src').rglob('*.java')),*list((REPO/'scripts/ai/evaluation').rglob('*.py')),
                  *list((REPO/'scripts/ai/evaluation').rglob('*.json')),*list((REPO/'scripts/ai/evaluation').rglob('*.jsonl'))]
    sources={str(f.relative_to(REPO)).replace('\\','/'):evaluator.digest(f) for f in source_files}
    (out/'sources-before.json').write_text(json.dumps(sources,indent=2),encoding='utf-8')
    shutil.copyfile(graph_manifest,out/'graph-manifest.json')
    state=dict(phase='preflight',modelManifestSha256=evaluator.digest(model_manifest),osrmId=entry['id'],osrmImage=entry['imageId'],graphManifestSha256=evaluator.digest(graph_manifest),cpuCount=os.cpu_count(),commands=[],ownedOllamaPid=None,ownedOsrm=False)
    def save():
        (out/'status.json').write_text(json.dumps(state,indent=2),encoding='utf-8')
    def phase(name):
        state['phase']=name;save();print(name,flush=True)
    def run(name,cmd,expected=(0,),extra_env=None,cwd=REPO,timeout=15000):
        phase(name)
        with (out/(name+'.log')).open('xb') as log:
            r=subprocess.Popen(cmd,cwd=cwd,env=extra_env or env,stdout=log,stderr=subprocess.STDOUT,creationflags=FLAGS)
            try:r.wait(timeout=timeout)
            finally:
                if r.poll() is None:
                    subprocess.run(['taskkill','/PID',str(r.pid),'/T','/F'],capture_output=True,timeout=20,creationflags=FLAGS)
                    r.wait(timeout=20)
        state['commands'].append(dict(name=name,exit=r.returncode,command=[str(c) for c in cmd]));save()
        require(r.returncode in expected,'Command failed: '+name)
        return r.returncode
    def maven(name,options,extra_env=None):
        result=run(name,[str(REPO/'backend/mvnw.cmd'),'-o',*options],extra_env=extra_env,cwd=REPO/'backend')
        dest=out/(name+'-xml');dest.mkdir()
        for f in (REPO/'backend/target/surefire-reports').glob('TEST-*.xml'):shutil.copyfile(f,dest/f.name)
        return result
    ollama=None;log=None;owned_osrm=False
    try:
        maven('backend-verify',['verify'])
        phase('starting-owned-providers');free_port(11434);free_port(5000)
        current=json.loads(docker('inspect',entry['id']))[0];guard.validate_osrm_reuse(entry,current,graph)
        docker('start',entry['id']);owned_osrm=True;state['ownedOsrm']=True;save()
        provider_env=dict(env);provider_env.update(OLLAMA_HOST='127.0.0.1:11434',OLLAMA_MODELS=str(args.models),OLLAMA_NO_CLOUD='true',OLLAMA_NOPRUNE='true',OLLAMA_MAX_LOADED_MODELS='1',OLLAMA_DEBUG='false',NO_PROXY='127.0.0.1,localhost')
        log=(out/'ollama.log').open('xb')
        ollama=subprocess.Popen([str(args.ollama_exe),'serve'],env=provider_env,stdout=log,stderr=subprocess.STDOUT,creationflags=FLAGS)
        state['ownedOllamaPid']=ollama.pid;save()
        ready=False
        for _ in range(45):
            require(ollama.poll() is None,'Owned Ollama exited')
            try:
                identity=evaluator.model_identity();ready=True;break
            except (OSError,ValueError,KeyError):time.sleep(1)
        require(ready,'Ollama not ready; no model pull')
        require(identity['digest']==state['modelManifestSha256'],'model manifest/API digest mismatch')
        state['model']=identity;state['initialLoadState']=evaluator.model_load_state(identity);save()
        maven('parser-live',['-Dtest=AiLiveBaselineIT','-Dai.eval.output='+str(out/'parser'),'test'])
        maven('scenario-live',['-Dtest=AiEvaluationFixtureTest','-Dai.eval.liveQwen=true','-Dai.eval.output='+str(out/'scenario-capture'),'test'])
        run('scenario-score',[sys.executable,'-X','utf8',str(REPO/'scripts/ai/evaluation/run_evaluation.py'),'--dataset',str(REPO/'scripts/ai/evaluation/scenarios-v1'),'--suites','recommendation,itinerary','--capture',str(out/'scenario-capture/capture.json'),'--output',str(out/'scenario-score'),'--allow-draft'],(0,1))
        routing_env=dict(env);routing_env['KDRIVE_ROUTING_MODE']='online'
        method='onlineRoutePreview_RequiresLiveOsrm_MustBeVerified_BothDirectionsAndMultiPoint'
        maven('routing-live',['-Dtest=LocationRouteHttpServerTest#'+method,'test'],routing_env)
        suite=ET.parse(out/'routing-live-xml/TEST-com.carrental.LocationRouteHttpServerTest.xml').getroot()
        require([int(suite.attrib[k]) for k in ('tests','failures','errors','skipped')]==[1,0,0,0],'real OSRM test did not execute')
        require(evaluator.model_identity()==identity,'model identity drift')
        require(evaluator.digest(model_manifest)==state['modelManifestSha256'],'model manifest drift')
        for relative,h in sources.items():require(evaluator.digest(REPO/relative)==h,'source/data drift')
        phase('BASELINE_COLLECTED_EXPLORATORY')
    finally:
        errors=[]
        if ollama is not None:
            if ollama.poll() is None:
                result=subprocess.run(['taskkill','/PID',str(ollama.pid),'/T','/F'],capture_output=True,timeout=20,creationflags=FLAGS)
                try:ollama.wait(timeout=20)
                except subprocess.TimeoutExpired:errors.append('owned Ollama did not exit')
            state['ollamaExit']=ollama.poll()
        if log is not None:log.close()
        if owned_osrm:
            try:
                current=json.loads(docker('inspect',entry['id']))[0];require(current['Id']==entry['id'],'owned identity drift')
                if current['State']['Running']:docker('stop',entry['id'])
                state['osrmStopped']=not json.loads(docker('inspect',entry['id']))[0]['State']['Running']
                require(state['osrmStopped'],'owned OSRM did not stop')
            except Exception:errors.append('owned OSRM cleanup failed')
        state['cleanupErrors']=errors;save()
        require(not errors,'provider cleanup failed')
    print(json.dumps({'status':state['phase'],'certified':False,'evidence':str(out)}))


if __name__=='__main__':main()
