using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace CampusHospitality.Desktop
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            try
            {
                var appDirectory = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
                string dataDirectory = FindDataDirectory(appDirectory);
                Directory.CreateDirectory(dataDirectory);

                using (var instanceLock = AcquireDataLock(dataDirectory))
                using (var backend = new LocalBackend(appDirectory.FullName, dataDirectory))
                using (var window = new DesktopWindow(backend, dataDirectory))
                {
                    Application.Run(window);
                }
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "学宾接待启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static string FindDataDirectory(DirectoryInfo appDirectory)
        {
            string configured = Environment.GetEnvironmentVariable("CAMPUS_HOSPITALITY_DATA_DIR");
            if (!String.IsNullOrWhiteSpace(configured))
                return Path.GetFullPath(Path.Combine(appDirectory.FullName, configured));

            // Share the project data directory with the development start script.
            DirectoryInfo source = appDirectory.Parent;
            if (source != null && File.Exists(Path.Combine(source.FullName, "backend", "pom.xml")))
                return Path.Combine(source.FullName, "data");

            return Path.Combine(appDirectory.FullName, "data");
        }

        private static FileStream AcquireDataLock(string dataDirectory)
        {
            try
            {
                return File.Open(Path.Combine(dataDirectory, "desktop.lock"), FileMode.OpenOrCreate,
                    FileAccess.ReadWrite, FileShare.None);
            }
            catch (IOException error)
            {
                throw new IOException("无法锁定数据目录。请确认没有其他学宾接待窗口正在使用它。", error);
            }
        }
    }

    internal sealed class DesktopWindow : Form
    {
        private readonly LocalBackend backend;
        private readonly string profileDirectory;
        private readonly WebView2 view;
        private readonly Label status;

        public DesktopWindow(LocalBackend backend, string dataDirectory)
        {
            this.backend = backend;
            profileDirectory = Path.Combine(dataDirectory, "webview2");
            Text = "学宾接待";
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(1280, 820);
            MinimumSize = new Size(760, 520);
            BackColor = Color.FromArgb(244, 246, 251);

            view = new WebView2 { Dock = DockStyle.Fill };
            status = new Label
            {
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                Font = new Font("Microsoft YaHei UI", 11F),
                ForeColor = Color.FromArgb(24, 36, 62),
                Text = "正在启动学宾接待…"
            };
            Controls.Add(view);
            Controls.Add(status);
            status.BringToFront();
            Shown += StartApplication;
        }

        private async void StartApplication(object sender, EventArgs e)
        {
            try
            {
                var environment = await CoreWebView2Environment.CreateAsync(null, profileDirectory);
                if (IsDisposed) return;
                await view.EnsureCoreWebView2Async(environment);
                if (IsDisposed) return;

                int port = await backend.StartAsync();
                if (IsDisposed) return;
                var origin = new Uri("http://127.0.0.1:" + port + "/");
                var browser = view.CoreWebView2;
                if (browser == null)
                    throw new InvalidOperationException("WebView2 界面进程已停止。请关闭窗口后重试。");
                browser.Settings.AreDevToolsEnabled = false;
                browser.Settings.IsStatusBarEnabled = false;
                browser.NavigationStarting += delegate(object source, CoreWebView2NavigationStartingEventArgs args)
                {
                    args.Cancel = !IsLocalPage(args.Uri, origin) && !IsTelephoneLink(args.Uri);
                };
                browser.LaunchingExternalUriScheme += delegate(object source, CoreWebView2LaunchingExternalUriSchemeEventArgs args)
                {
                    args.Cancel = !IsTelephoneLink(args.Uri);
                };
                browser.NewWindowRequested += delegate(object source, CoreWebView2NewWindowRequestedEventArgs args)
                {
                    args.Handled = true;
                    if (IsLocalPage(args.Uri, origin)) browser.Navigate(args.Uri);
                };
                browser.NavigationCompleted += delegate(object source, CoreWebView2NavigationCompletedEventArgs args)
                {
                    if (args.IsSuccess)
                    {
                        status.Hide();
                        view.Focus();
                    }
                    else if (status.Visible)
                    {
                        status.Text = "无法载入软件界面。\n请关闭窗口后重试。";
                    }
                };
                browser.Navigate(origin.AbsoluteUri);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                if (!IsDisposed) status.Text = "此电脑需要安装 Microsoft Edge WebView2 运行时。\n安装后重新打开学宾接待。";
            }
            catch (Exception error)
            {
                if (!IsDisposed) status.Text = error.Message + "\n\n启动日志 " + backend.LogPath;
            }
        }

        private static bool IsLocalPage(string address, Uri origin)
        {
            Uri uri;
            return Uri.TryCreate(address, UriKind.Absolute, out uri) && uri.Scheme == origin.Scheme &&
                uri.Host == origin.Host && uri.Port == origin.Port;
        }

        private static bool IsTelephoneLink(string address)
        {
            return address.StartsWith("tel:", StringComparison.OrdinalIgnoreCase);
        }
    }

    internal sealed class LocalBackend : IDisposable
    {
        private const string ReadyPrefix = "CAMPUS_HOSPITALITY_READY=";
        private readonly string appDirectory;
        private readonly string dataDirectory;
        private readonly TaskCompletionSource<int> ready = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly object logLock = new object();
        private Process process;
        private StreamWriter log;
        private bool started;

        public LocalBackend(string appDirectory, string dataDirectory)
        {
            this.appDirectory = appDirectory;
            this.dataDirectory = dataDirectory;
            LogPath = Path.Combine(dataDirectory, "desktop-backend.log");
        }

        public string LogPath { get; private set; }

        public async Task<int> StartAsync()
        {
            string java = Path.Combine(appDirectory, "runtime", "bin", "java.exe");
            if (!File.Exists(java) || !File.Exists(Path.Combine(appDirectory, "hospitality.jar")))
                throw new InvalidOperationException("桌面包文件不完整。请在完整的 release 文件夹中启动软件。");

            log = new StreamWriter(LogPath, false, new UTF8Encoding(false)) { AutoFlush = true };
            var settings = new ProcessStartInfo
            {
                FileName = java,
                WorkingDirectory = appDirectory,
                Arguments = "-Dfile.encoding=UTF-8 -jar hospitality.jar --server.address=127.0.0.1 --server.port=0 " +
                    "--app.desktop=true --server.shutdown=graceful --spring.lifecycle.timeout-per-shutdown-phase=8s",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            settings.EnvironmentVariables["CAMPUS_HOSPITALITY_DATA_DIR"] = dataDirectory.Replace('\\', '/');
            process = new Process { StartInfo = settings, EnableRaisingEvents = true };
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs args) { ReadLine(args.Data); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs args) { ReadLine(args.Data); };
            process.Exited += delegate { ready.TrySetException(new InvalidOperationException("本地服务启动失败，请检查启动日志。")); };
            started = process.Start();
            if (!started) throw new InvalidOperationException("无法启动本地服务。");
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            if (await Task.WhenAny(ready.Task, Task.Delay(TimeSpan.FromSeconds(60))) != ready.Task)
                throw new TimeoutException("本地服务启动超时，请检查启动日志。");
            return await ready.Task;
        }

        private void ReadLine(string line)
        {
            if (line == null) return;
            lock (logLock)
            {
                if (log != null)
                {
                    try { log.WriteLine(line); }
                    catch (IOException) { /* Logging cannot stop the desktop process. */ }
                }
            }

            int port;
            if (line.StartsWith(ReadyPrefix, StringComparison.Ordinal) &&
                Int32.TryParse(line.Substring(ReadyPrefix.Length), out port) && port > 0 && port <= 65535)
                ready.TrySetResult(port);
        }

        public void Dispose()
        {
            if (process != null)
            {
                try
                {
                    if (started && !process.HasExited)
                    {
                        process.StandardInput.Close();
                        if (!process.WaitForExit(12000))
                        {
                            process.Kill();
                            process.WaitForExit(2000);
                        }
                    }
                }
                catch (InvalidOperationException) { /* The process already exited. */ }
                finally { process.Dispose(); }
            }
            lock (logLock)
            {
                if (log != null) log.Dispose();
                log = null;
            }
        }
    }
}
