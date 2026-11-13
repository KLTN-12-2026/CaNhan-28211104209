"""Live metadata and lifecycle preflight guards; no model or container is started."""
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import run_evaluation as evaluator
import run_live_baseline as live


class LiveBaselineTests(unittest.TestCase):
    identity=dict(name='qwen3:8b',digest='a'*64,runtimeVersion='0.0.1',mode='live')

    def test_pinned_identity(self):
        with patch.object(evaluator,'ollama_json',side_effect=[{'models':[dict(self.identity)]},{'version':'0.0.1'}]) as get:
            self.assertEqual(evaluator.model_identity(),self.identity)
            self.assertEqual([c.args[0] for c in get.call_args_list],['/api/tags','/api/version'])

    def test_missing_or_duplicate_model_refused_without_pull(self):
        for models in ([],[self.identity,self.identity]):
            with patch.object(evaluator,'ollama_json',return_value={'models':models}) as get,self.assertRaises(ValueError):
                evaluator.model_identity()
            self.assertEqual(get.call_count,1)

    def test_unpinned_identity_refused(self):
        for key,value in [('digest','latest'),('name','different:8b'),('runtimeVersion','secret\ntext'),('mode','synthetic')]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                evaluator.validate_model(dict(self.identity,**{key:value}))

    def test_load_state_is_observed_not_guessed(self):
        for models,expected in [([], 'COLD'),([self.identity],'WARM'),([dict(self.identity,digest='b'*64)],'UNKNOWN'),([self.identity,self.identity],'UNKNOWN')]:
            with patch.object(evaluator,'ollama_json',return_value={'models':models}):
                self.assertEqual(evaluator.model_load_state(self.identity),expected)
        with patch.object(evaluator,'ollama_json',side_effect=OSError):
            self.assertEqual(evaluator.model_load_state(self.identity),'UNKNOWN')

    def test_environment_is_copied_and_external_overrides_removed(self):
        values={'KDRIVE_TEST_DB_URL':'forbidden','SPRING_DATASOURCE_URL':'forbidden','OLLAMA_HOST':'remote','HTTPS_PROXY':'remote','KEEP':'yes'}
        with patch.dict(os.environ,values,clear=True):
            env=live.child_environment(Path('jdk'))
            self.assertEqual(dict(os.environ),values)
        self.assertFalse(set(values)-{'KEEP'} & set(env))
        self.assertEqual(env['KEEP'],'yes');self.assertEqual(env['KDRIVE_EVAL_LIVE'],'1')

    def test_output_requires_new_runtime_path(self):
        for path in (live.REPO,live.REPO/'.runtime',live.REPO/'outside',live.REPO/'.runtime/unsafe&path'):
            with self.subTest(path=path),self.assertRaises(ValueError):live.output_path(path)

    def test_occupied_port_refused(self):
        with patch.object(live.socket,'socket') as sock:
            sock.return_value.__enter__.return_value.connect_ex.return_value=0
            with self.assertRaises(ValueError):live.free_port(11434)


if __name__=='__main__':unittest.main()
