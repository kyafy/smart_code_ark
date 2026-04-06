import java.net.URI;
public class TestDockerURI {
    public static void main(String[] args) {
        URI uri = URI.create("unix:///var/run/docker.sock");
        System.out.println("Scheme: " + uri.getScheme());
        System.out.println("Host: " + uri.getHost());
        System.out.println("Path: " + uri.getPath());
        System.out.println("Port: " + uri.getPort());
    }
}
