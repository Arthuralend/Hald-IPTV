using System.Windows;

namespace HaldIPTV;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        LibVLCSharp.Shared.Core.Initialize();
        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(args.Exception.Message, Loc.T("Телевизор"), MessageBoxButton.OK, MessageBoxImage.Warning);
            args.Handled = true;
        };
        base.OnStartup(e);
    }
}
