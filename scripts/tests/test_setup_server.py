"""用假的系统命令验证环境脚本，所有配置和 Swap 都写在临时目录。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
FAKE_COMMAND = r'''#!PYTHON
import json, sys
from pathlib import Path
root = Path(sys.argv[0]).absolute().parent.parent
scenario = json.loads((root/'scenario.json').read_text())
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root/'commands.jsonl').open('a') as handle:
    handle.write(json.dumps([command,*args])+'\n')
def stop(): sys.exit(2)
if command == 'uname': print('Linux')
elif command == 'dpkg': print(scenario.get('arch','amd64'))
elif command == 'dpkg-query':
    if args[-1] in scenario.get('conflicts',[]): print('install ok installed')
    else: sys.exit(1)
elif command == 'apt-get':
    if scenario.get('apt_failure'): stop()
    if 'docker-ce' in args: (root/'docker-installed').touch()
elif command == 'curl':
    if scenario.get('curl_failure'): stop()
    Path(args[args.index('-o')+1]).write_text('fixture signing key')
elif command == 'systemctl':
    if scenario.get('service_failure'): stop()
elif command == 'docker':
    if args == ['compose','version']:
        if not scenario.get('installed') and not (root/'docker-installed').exists(): stop()
        print('Docker Compose version v2.test')
    elif args == ['info']:
        if scenario.get('daemon_failure'): stop()
    else: stop()
elif command == 'swapon':
    if args == ['--noheadings','--show=NAME']:
        if scenario.get('active_swap') or (root/'swap-active').exists(): print('/existing-swap')
    elif args == ['--show']: pass
    else:
        if scenario.get('swapon_failure'): stop()
        (root/'swap-active').touch()
elif command == 'findmnt': print(scenario.get('filesystem','ext4'))
elif command == 'df':
    if '-Pk' in args: print('Filesystem 1024-blocks Used Available Capacity Mounted\n/dev/test 99999999 100 '+str(scenario.get('free_kb',30000000))+' 1% /')
    else: print('fixture disk usage')
elif command == 'dd':
    if scenario.get('dd_failure'): stop()
    path=next(arg[3:] for arg in args if arg.startswith('of='))
    Path(path).write_bytes(b'fixture swap allocation')
elif command == 'mkswap':
    if scenario.get('mkswap_failure'): stop()
elif command in ('git','nano','free'): print('fixture '+command)
else: stop()
'''


class SetupServerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='wargame-environment-test-')
        self.root = Path(self.temporary.name)
        for directory in ('bin','sources','keys','work','systemd'):
            (self.root/directory).mkdir()
        (self.root/'os-release').write_text('ID=ubuntu\nVERSION_ID=22.04\nVERSION_CODENAME=jammy\n')
        (self.root/'fstab').write_text('# fixture original fstab\n')
        fake = self.root/'fake.py'
        fake.write_text(FAKE_COMMAND.replace('#!PYTHON','#!'+sys.executable))
        fake.chmod(0o755)
        for name in ('uname','dpkg','dpkg-query','apt-get','curl','systemctl','docker','swapon','findmnt','df','dd','mkswap','git','nano','free'):
            (self.root/'bin'/name).symlink_to(fake)
        self.environment = dict(os.environ, PATH=str(self.root/'bin')+os.pathsep+os.environ['PATH'])
        self.scenario = {}

    def tearDown(self):
        self.temporary.cleanup()

    def execute(self, commands, swap=0):
        (self.root/'scenario.json').write_text(json.dumps(self.scenario))
        script = '''source "$1"
SETUP_OS_RELEASE="$2/os-release"
SETUP_SYSTEMD_DIR="$2/systemd"
SETUP_SOURCE_DIR="$2/sources"
SETUP_MAIN_SOURCE="$2/main-source"
SETUP_KEYRING_DIR="$2/keys"
SETUP_WORK_DIR="$2/work"
SETUP_SWAPFILE="$2/swapfile"
SETUP_FSTAB="$2/fstab"
SETUP_SWAP_GB="$3"
''' + commands
        return subprocess.run(['/bin/bash','-c',script,'test',str(ROOT/'setup-server.sh'),str(self.root),str(swap)],env=self.environment,capture_output=True,text=True)

    def calls(self):
        path=self.root/'commands.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_ubuntu_installs_official_runtime_and_verifies(self):
        result=self.execute('detect_platform; plan_swap; install_runtime; configure_swap; verify_runtime')
        self.assertEqual(0,result.returncode,result.stderr)
        config=(self.root/'sources/wargame-docker.sources').read_text()
        self.assertIn('linux/ubuntu',config)
        self.assertIn('Suites: jammy',config)
        self.assertIn('docker-compose-plugin',str(self.calls()))
        self.assertFalse((self.root/'swapfile').exists())

    def test_debian_uses_bookworm_and_arm64(self):
        (self.root/'os-release').write_text('ID=debian\nVERSION_ID=12\nVERSION_CODENAME=bookworm\n')
        self.scenario['arch']='arm64'
        result=self.execute('detect_platform; install_runtime')
        self.assertEqual(0,result.returncode,result.stderr)
        config=(self.root/'sources/wargame-docker.sources').read_text()
        self.assertIn('linux/debian',config)
        self.assertIn('Suites: bookworm',config)
        self.assertIn('Architectures: arm64',config)

    def test_unsupported_system_stops_before_install(self):
        (self.root/'os-release').write_text('ID=ubuntu\nVERSION_ID=20.04\nVERSION_CODENAME=focal\n')
        result=self.execute('detect_platform; install_runtime')
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(any(call[0]=='apt-get' for call in self.calls()))

    def test_existing_runtime_does_not_replace_docker_packages_or_repository(self):
        self.scenario.update(installed=True,conflicts=['docker.io'])
        result=self.execute('detect_platform; install_runtime; verify_runtime')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse((self.root/'sources/wargame-docker.sources').exists())
        self.assertFalse(any('docker-ce' in call for call in self.calls()))
        self.assertFalse(any(call[0]=='systemctl' and 'restart' in call for call in self.calls()))

    def test_conflicting_package_stops_without_uninstall(self):
        self.scenario['conflicts']=['docker.io']
        result=self.execute('detect_platform; install_runtime')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('不会自动卸载',result.stderr)
        self.assertFalse(any(call[0]=='apt-get' for call in self.calls()))

    def test_foreign_repository_is_not_overwritten(self):
        file=self.root/'sources/docker.sources'
        file.write_text('URIs: https://download.docker.com/linux/ubuntu\n')
        result=self.execute('detect_platform; install_runtime')
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(any(call[0]=='apt-get' for call in self.calls()))
        self.assertIn('URIs:',file.read_text())

    def test_command_failures_never_continue_to_success(self):
        for flag in ('apt_failure','curl_failure','service_failure','daemon_failure'):
            with self.subTest(flag=flag):
                self.scenario={flag:True}
                (self.root/'docker-installed').unlink(missing_ok=True)
                result=self.execute('detect_platform; install_runtime; verify_runtime; echo SUCCESS')
                self.assertNotEqual(0,result.returncode)
                self.assertNotIn('SUCCESS',result.stdout)

    def test_swap_is_private_persistent_and_preserves_fstab_backup(self):
        before=(self.root/'fstab').read_bytes()
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(0o600,(self.root/'swapfile').stat().st_mode & 0o777)
        self.assertIn(str(self.root/'swapfile')+' none swap sw 0 0',(self.root/'fstab').read_text())
        self.assertEqual(before,next(self.root.glob('fstab.wargame-backup.*')).read_bytes())
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(1,(self.root/'fstab').read_text().count('none swap sw'))

    def test_active_swap_is_untouched(self):
        self.scenario['active_swap']=True
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse((self.root/'swapfile').exists())
        self.assertFalse(any(call[0]=='dd' for call in self.calls()))

    def test_existing_swapfile_is_not_overwritten(self):
        (self.root/'swapfile').write_text('existing content')
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual('existing content',(self.root/'swapfile').read_text())

    def test_existing_fstab_entry_without_active_swap_stops(self):
        before=f'{self.root}/swapfile none swap sw 0 0\n'
        (self.root/'fstab').write_text(before)
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('fstab 已有',result.stderr)
        self.assertEqual(before,(self.root/'fstab').read_text())
        self.assertFalse((self.root/'swapfile').exists())

    def test_missing_fstab_stops_before_allocating_swap(self):
        (self.root/'fstab').unlink()
        result=self.execute('plan_swap; configure_swap',swap=2)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('fstab',result.stderr)
        self.assertFalse((self.root/'swapfile').exists())

    def test_low_disk_and_unsupported_filesystem_do_not_allocate(self):
        for scenario in ({'free_kb':1000},{'filesystem':'btrfs'}):
            with self.subTest(scenario=scenario):
                self.scenario=scenario
                result=self.execute('plan_swap; configure_swap',swap=2)
                self.assertNotEqual(0,result.returncode)
                self.assertFalse((self.root/'swapfile').exists())

    def test_failed_swap_creation_cleans_only_new_file(self):
        for flag in ('dd_failure','mkswap_failure','swapon_failure'):
            with self.subTest(flag=flag):
                self.scenario={flag:True}
                result=self.execute('plan_swap; configure_swap',swap=2)
                self.assertNotEqual(0,result.returncode)
                self.assertFalse((self.root/'swapfile').exists())
                self.assertNotIn('none swap sw',(self.root/'fstab').read_text())

    def test_check_function_only_queries_runtime(self):
        self.scenario['installed']=True
        result=self.execute('detect_platform; verify_runtime')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse(any(call[0] in ('apt-get','systemctl','curl','dd') for call in self.calls()))

    def test_cli_help_and_invalid_arguments(self):
        result=subprocess.run(['/bin/bash',str(ROOT/'setup-server.sh'),'--help'],capture_output=True,text=True)
        self.assertEqual(0,result.returncode)
        for args in (['--swap-gb','0'],['--swap-gb','17'],['--swap-gb'],['--check','--swap-gb','2'],['--unknown']):
            result=subprocess.run(['/bin/bash',str(ROOT/'setup-server.sh'),*args],capture_output=True,text=True)
            self.assertNotEqual(0,result.returncode)


if __name__=='__main__':
    unittest.main()
