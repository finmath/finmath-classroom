# Deploy on Ubuntu with Plesk

Run one Java process under systemd, with SQLite and lecture files in a private
directory. Plesk supplies HTTPS and forwards requests to the loopback listener.
The application needs Java 17 or newer; build the JAR on your development machine.
The supplied service uses Ubuntu's Java 21 runtime at an explicit path on amd64.
For another architecture or runtime, adjust that path in the service and server
commands below.

These instructions use `classroom.example.org` and port `8081`. Replace the host
with your own subdomain, and check that the port is free. Server commands run as
root (or in a temporary `sudo -i` shell from your personal administrator account).
The service itself runs as the separate `finmath-classroom` user. For everyday
administration, use the [CLI helper](#short-server-cli) and
[personal SSH account](#personal-ssh-account) described below.

| Location | Contents | Owner |
| --- | --- | --- |
| `/opt/finmath-classroom/finmath-classroom.jar` | Executable application | root |
| `/etc/finmath-classroom/classroom.env` | Environment and GitHub credentials, mode `0600` | root |
| `/var/lib/finmath-classroom/` | `lectures/`, `classroom.db` and optional `feedback.db`, directory mode `0700` | finmath-classroom |
| `/etc/systemd/system/finmath-classroom.service` | Service definition | root |
| `/etc/systemd/system/finmath-classroom-feedback.{service,timer}` | Optional feedback collector and schedule | root |

## 1. Prepare the files

On the development machine, from this repository:

```sh
mvn -B -ntp verify
java -jar target/finmath-classroom.jar registration check --data local/teaching
```

Replace `local/teaching` with your private data directory if different. Review the
organization, staff team, template, branch, prefix and deadline before importing;
the database freezes these settings. See [registration configuration](registration.md).

Copy [classroom.env.example](../deploy/classroom.env.example) into your private
data directory as `production.env`, unless you already have one. Edit it locally:

```text
CLASSROOM_DATA=/var/lib/finmath-classroom
CLASSROOM_PUBLIC_URL=https://classroom.example.org
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8081
CLASSROOM_GITHUB_CLIENT_ID=your-client-id
CLASSROOM_GITHUB_CLIENT_SECRET=your-client-secret
CLASSROOM_GITHUB_TOKEN=your-provisioning-token
```

Use plain `KEY=value` lines, without `export`, shell commands, or `$VARIABLE`
references. systemd reads this file directly; it does not execute a shell. Keep
the local file private (`chmod 600`) and outside version control.

In your GitHub sign-in application's settings, set the homepage to the public
origin and the callback to:

```text
https://classroom.example.org/auth/github/callback
```

An OAuth application is the documented starting point. A GitHub App can supply
the same web sign-in client ID and client secret; use its **Client ID**, not its
App ID. Expiring user access tokens are compatible: sign-in fetches the identity
and discards the token. Verify the actual application in the learner pilot.
See [GitHub's user authorization flow](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app).

Provisioning still needs the separate token described in the
[permission table](registration.md#provisioning-token). GitHub App private keys
and installation IDs are not read by this service; automatic installation-token
creation and renewal are not implemented. Moodle settings can remain unset for
ordinary assignment links. Classroom does not need an SMTP account.

## 2. Transfer and install

First check the server:

```sh
java -version
ss -ltnp | grep -E ':(80|443|8080|8081)[[:space:]]'
getent ahostsv4 github.com
systemctl is-active nginx
```

Install Java 21 on Ubuntu 24.04. If the server already has a default Java used by
other applications, retain its executable before installation and restore that
selection afterwards:

```sh
classroom_previous_java=
if [ -x /usr/bin/java ]; then
  classroom_previous_java="$(readlink -f /usr/bin/java)"
fi
apt-get update
apt-get install openjdk-21-jre-headless
if [ -n "$classroom_previous_java" ]; then
  update-alternatives --set java "$classroom_previous_java"
fi
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version
```

Restoring the `java` alternative makes its selection manual; it does not restore
other Java utilities' alternatives. Classroom uses the explicit
[Ubuntu Java 21 executable](https://packages.ubuntu.com/noble/amd64/openjdk-21-jre-headless/filelist)
for the server and administrative commands, independently of `/usr/bin/java`.

Create a private transfer directory on the server:

```sh
install -d -m 700 /root/finmath-classroom-upload
```

From the development machine, copy only the deployment files and lecture data.
Replace `server.example.org` with the server's existing SSH hostname; the Classroom
subdomain does not need to exist yet:

```sh
scp -r target/finmath-classroom.jar deploy/finmath-classroom.service \
  local/teaching/production.env local/teaching/lectures \
  root@server.example.org:/root/finmath-classroom-upload/
```

For an existing live installation being moved, stop its service and transfer its
entire consistent data directory, including the database and any SQLite sidecar
files. Do not initialize an empty database in place of existing registrations.
The commands below describe a first installation.

On the server, create the account and install the files:

```sh
useradd --system --user-group --home-dir /var/lib/finmath-classroom \
  --no-create-home --shell /usr/sbin/nologin finmath-classroom
install -d -m 755 /opt/finmath-classroom
install -d -m 700 /etc/finmath-classroom
install -d -o finmath-classroom -g finmath-classroom -m 700 /var/lib/finmath-classroom
install -m 644 /root/finmath-classroom-upload/finmath-classroom.jar /opt/finmath-classroom/
install -m 600 /root/finmath-classroom-upload/production.env /etc/finmath-classroom/classroom.env
install -m 644 /root/finmath-classroom-upload/finmath-classroom.service /etc/systemd/system/
cp -R /root/finmath-classroom-upload/lectures /var/lib/finmath-classroom/
chown -R finmath-classroom:finmath-classroom /var/lib/finmath-classroom
chmod -R go-rwx /var/lib/finmath-classroom
systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/finmath-classroom.service
```

Skip account creation if this dedicated account already exists. The service
definition gives Java a private temporary directory and writable state directory;
the application and system files remain read-only to the service.

## 3. Import and start

Validate the copied configuration under the service account:

```sh
runuser -u finmath-classroom -- /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -jar /opt/finmath-classroom/finmath-classroom.jar \
  registration check --data /var/lib/finmath-classroom
```

Import explicitly. This transient unit reads the same private environment file
without sourcing it in a shell or placing credentials on the command line:

```sh
systemd-run --unit=finmath-classroom-import --wait --pipe --collect \
  --property=User=finmath-classroom --property=Group=finmath-classroom \
  --property=UMask=0077 \
  --property=EnvironmentFile=/etc/finmath-classroom/classroom.env \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /opt/finmath-classroom/finmath-classroom.jar \
  registration import --data /var/lib/finmath-classroom
```

Import creates `classroom.db`; it does not create student repositories. Supplied
roster GitHub usernames require read-only GitHub lookups. After a successful import:

```sh
systemctl enable --now finmath-classroom.service
systemctl status finmath-classroom.service --no-pager
curl --fail --show-error --retry 20 --retry-connrefused \
  --retry-delay 1 --max-time 5 http://127.0.0.1:8081/ready
curl --fail --show-error http://127.0.0.1:8081/health
```

The readiness request retries while Java starts; `systemctl start` returns before
the HTTP listener is ready.

If startup fails, inspect `journalctl -u finmath-classroom.service -n 80 --no-pager`.
`/ready` checks local registration configuration, not remote token permissions.
The application has no homepage at `/`; a 404 there does not mean startup failed.

## 4. Configure Plesk HTTPS

Create the `classroom` subdomain in Plesk with website hosting. Its DNS A record
must point to the server; add AAAA only if IPv6 is configured and reachable.
Install the free Let's Encrypt certificate for the exact subdomain, enable SSL/TLS,
and enable Plesk's permanent HTTP-to-HTTPS redirect. PHP is unnecessary for this
subdomain.

In **Apache & nginx Settings**, add the following under **Additional directives
for HTTPS**. These are directives inside Plesk's existing virtual host; do not
add a `<VirtualHost>` wrapper:

```apache
ProxyRequests Off
ProxyPreserveHost On
RequestHeader set X-Forwarded-Proto "https"
ProxyPass "/.well-known/acme-challenge/" "!"
ProxyPass "/" "http://127.0.0.1:8081/"
ProxyPassReverse "/" "http://127.0.0.1:8081/"
LimitRequestBody 131072
```

The ACME exception must precede the catch-all proxy rule so certificate renewal
can serve its challenge locally. Apache needs `proxy`, `proxy_http` and `headers`.
If a module is missing, enable it with `a2enmod`, validate with `apache2ctl -t`,
and reload Apache after a successful configuration test.

If Plesk also runs nginx, keep **Proxy mode** enabled for this subdomain; disable
**Smart static files processing**, **Serve static files directly by nginx**, and
nginx caching for it. All application paths must reach Java. Keep port 8081 bound
to loopback; it does not need a public firewall rule.

Before distributing assignment links, configure this subdomain's proxy logs to
omit query strings and request bodies, including OAuth callbacks and key-prefilled
URLs. A path-only access format uses `%U` instead of `%r`/`%q` in Apache, or `$uri`
instead of `$request`/`$request_uri` in nginx; omit the Referer header too. Check
all active log directives: adding a second safe log does not sanitize an existing
log. Plesk generates virtual-host files, so use its supported configuration hooks
and verify the resulting logs. Configure request rate limits for the public host;
the supplied service and body-size limit do not implement rate limiting.

References: [Plesk custom directives](https://support.plesk.com/hc/en-us/articles/12377861295895-How-to-add-custom-Apache-nginx-configuration-for-a-domain-in-Plesk),
[Apache proxy rules](https://httpd.apache.org/docs/2.4/mod/mod_proxy.html),
[systemd environment and directories](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html).

## 5. Verify the public flow

From another machine:

```sh
curl --fail --show-error https://classroom.example.org/health
curl --fail --show-error https://classroom.example.org/ready
```

Open a configured `/accept/<lecture>/<assignment>` URL in a browser. Complete a
learner-account acceptance, accept the invitation, and verify private repository
visibility, the template contents, staff access and student write access. Returning
to the assignment should show the saved repository. This is the live test of DNS,
TLS, OAuth, permissions and provisioning; passing local tests does not replace it.

Use the plain assignment URL in Moodle initially. Keys can be entered by the
student or [prefilled in a course-only link](registration.md#prefilling-registration-keys).
LTI identity can be configured separately later.

## 6. Enable automatic feedback collection

Deploy the JAR containing `feedback sync` before installing the timer. Collection
uses the same private configuration and enrollments, plus a separate `feedback.db`;
no registration import or student workflow change is required for this feature.
Results remain untrusted student feedback, separate from final grades.

The credential needs **Actions: read** access to the enrolled private student
repositories and repository metadata. Set `CLASSROOM_FEEDBACK_TOKEN` in the private
environment file for a dedicated read-only credential, or grant that additional
read permission to the existing `CLASSROOM_GITHUB_TOKEN` used as fallback. The
student OAuth token is not used. Token renewal is still the operator's responsibility.
See [GitHub artifact permissions](https://docs.github.com/en/rest/actions/artifacts).

The default workflow filename is `classroom.yml`. If your templates use another
shared filename, set `CLASSROOM_FEEDBACK_WORKFLOW` in the environment file. The
collector accepts push runs on the configured assignment branch; it does not collect
pull-request feedback. It can retrieve existing completed runs while their artifacts
are still available.

Copy the two unit files from the development machine:

```sh
scp deploy/finmath-classroom-feedback.service deploy/finmath-classroom-feedback.timer \
  root@YOUR_SERVER:/root/finmath-classroom-upload/
```

On the server, install them and run one collection before enabling the schedule:

```sh
install -m 644 /root/finmath-classroom-upload/finmath-classroom-feedback.service /etc/systemd/system/
install -m 644 /root/finmath-classroom-upload/finmath-classroom-feedback.timer /etc/systemd/system/
systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/finmath-classroom-feedback.service \
  /etc/systemd/system/finmath-classroom-feedback.timer
systemctl start finmath-classroom-feedback.service
journalctl -u finmath-classroom-feedback.service -n 30 --no-pager
```

The oneshot unit normally becomes `inactive (dead)` after a successful collection.
Check its exit status and the saved results; a stopped oneshot is not a stopped web
application. List results without loading credentials:

```sh
runuser -u finmath-classroom -- /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -jar /opt/finmath-classroom/finmath-classroom.jar \
  feedback list --data /var/lib/finmath-classroom
```

For the first pilot, confirm a `received` row with the expected repository, commit,
run attempt and test score. Failed tests are valid feedback and should be collected.
`missing`, `expired`, `invalid` and `unavailable` remain visible without a score;
check the workflow's artifact upload and the credential's repository access.
`feedback show` prints a selected stored result, as described in the
[collection reference](autograding.md#5-automatic-server-collection).

Once the first collection is verified:

```sh
systemctl enable --now finmath-classroom-feedback.timer
systemctl list-timers finmath-classroom-feedback.timer --no-pager
```

The timer runs about five minutes after the previous collection finishes, with up
to 30 seconds of jitter. It starts after boot and retries following outages; saved
results are not downloaded again. API rate-limit delays persist across runs. A
30-minute service timeout bounds one scan; partial progress is retained. Monitor
the journal and collection checks for permission, pagination-limit and retention
problems. This is polling, not a GitHub webhook endpoint.

## Administration, updates and backups

### Short server CLI

The optional [classroom helper](../deploy/classroom) replaces the Java executable,
JAR and data arguments with a short command. It targets Ubuntu 24.04 and the paths
in this guide; it does not require a new JAR when installed separately.

After setting up a personal administrator account (here `fries`), copy the helper
from the laptop:

```sh
scp deploy/classroom fries@server.example.org:classroom-upload
```

On the server, as that administrator:

```sh
sudo install -o root -g root -m 755 ~/classroom-upload /usr/local/sbin/classroom
sudo classroom registration check
sudo classroom registration list --format table
sudo classroom feedback sync
sudo classroom feedback list --lecture numerical-methods
```

`classroom --help` lists the supported commands. Additional selectors work as in
the Java CLI; omit `--data`, which is fixed to `/var/lib/finmath-classroom`.
Feedback commands require the JAR version that implements them.

The helper starts a short-lived systemd service as `finmath-classroom`, loads the
root-only environment file only for `registration import`, `registration reconcile`
and `feedback sync`, and returns the application's exit code. Other commands run
without that credential file. It does not execute the file as shell code or run
Java as root. Literal command arguments are preserved with systemd's
`--expand-environment=no`, available in systemd 254 and newer.
See [systemd-run](https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html).

Stop the web service, feedback timer and collector before `registration import`,
`registration delete` or `registration reconcile`, and back up before changes.
The helper does not manage services automatically. These commands remain available
directly with Java if the helper is not installed.

### Personal SSH account

Use a personal account with `sudo` for administration, keeping the restricted
`finmath-classroom` account solely for the application. The examples below use
`fries`; choose a different name if that account is already used for another
purpose. Keep the current root SSH session open until a second session has
successfully tested the new login and `sudo`. Keep access to the provider's server
console available as well.

In the existing root session, change the root password interactively:

```sh
passwd root
```

Save the new password in your password manager. Do not put it in a command,
project file or chat. The command changes the Unix account password.
See [passwd](https://manpages.ubuntu.com/manpages/noble/man1/passwd.1.html).

For a new, unused account name, create the administrator and grant sudo access:

```sh
adduser fries
usermod -aG sudo fries
```

Choose a separate password for this account; it is also used by `sudo`. On the
laptop, either use an existing SSH key or create a dedicated, passphrase-protected
key at an unused filename:

```sh
ssh-keygen -t ed25519 -a 64 -f ~/.ssh/id_ed25519_strato -C 'fries-strato'
ssh-copy-id -i ~/.ssh/id_ed25519_strato.pub fries@server.example.org
ssh -i ~/.ssh/id_ed25519_strato -o IdentitiesOnly=yes \
  -o PreferredAuthentications=publickey -o PasswordAuthentication=no \
  fries@server.example.org
```

Do not overwrite an existing key. Only the `.pub` file is installed on the server;
the private key stays on the laptop. In the new SSH session, verify elevation:

```sh
sudo -v
sudo whoami
```

The last command must print `root`. Once both key authentication and sudo are
confirmed, use `sudoedit /etc/ssh/sshd_config.d/00-personal-admin.conf` to add:

```text
PermitRootLogin no
```

Then validate syntax and the effective setting before reloading:

```sh
sudo /usr/sbin/sshd -t
sudo /usr/sbin/sshd -T | grep '^permitrootlogin '
```

Expect `permitrootlogin no`. If a different value appears, inspect the main file
and earlier snippets; OpenSSH generally uses the first obtained value. Existing
`Match` blocks can override settings for particular connections; inspect those
too, using `sshd -T -C` for the relevant user/address. Once the configuration checks
pass, reload with `sudo systemctl reload ssh.service` and test another personal
SSH login before closing either existing session. This changes direct root SSH
access; it does not remove the root account or sudo access.
See [Ubuntu OpenSSH configuration](https://ubuntu.com/server/docs/how-to/security/openssh-server/)
and [sshd_config](https://manpages.ubuntu.com/manpages/noble/man5/sshd_config.5.html).

After disabling root SSH, replace the bootstrap guide's `scp root@...` commands
with transfers to your personal account. Upload privately into your home directory,
then install files with `sudo`; `/root/finmath-classroom-upload` is not directly
accessible to the personal account.

### Direct CLI and backups

List registrations on the server without loading credentials:

```sh
runuser -u finmath-classroom -- /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -jar /opt/finmath-classroom/finmath-classroom.jar \
  registration list --data /var/lib/finmath-classroom --format table
```

Use the [delete/recovery reference](registration.md#let-a-student-register-again)
for deliberate re-registration. Stop the web service and, if installed, the
feedback timer and collector before administrative deletion or configuration import.
Restart after editing configuration or credentials;
pending sign-ins will need to start again.

Back up `/var/lib/finmath-classroom` while both services and the feedback timer are
stopped, plus the private environment, service/timer definitions, deployed JAR and
Plesk proxy settings. Keep an
off-server copy and test restoration into a separate private directory. Plesk
website backups may not include these application paths automatically.

For an application update, build and verify locally, stop both services and the
timer, make the backup, replace only the JAR, then start the web service and timer
and repeat the health/ready and collection checks. Retain
the previous JAR for rollback; a version with a database migration also requires
a compatible database backup. Do not overwrite the database with an empty pilot.

The service runs independently of the SSH session and starts after a reboot.
Monitor the public health endpoint, DNS resolution, backups and credential expiry.
Automatic credential renewal and automated off-server backups are not supplied by
these examples.
