package net.shrimpworks.unreal.submitter;

import java.io.IOException;

import org.eclipse.egit.github.core.PullRequest;
import org.eclipse.egit.github.core.PullRequestMarker;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.PullRequestService;
import org.eclipse.egit.github.core.service.RepositoryService;

import static net.shrimpworks.unreal.submitter.Main.*;

public class GitHubAPITest {

	public static void main(String[] args) throws IOException {
		GitHubClient client = new GitHubClient().setCredentials(GH_USERNAME, GH_PASSWORD);
		RepositoryService repoService = new RepositoryService(client);
		Repository repo = repoService.getRepository(GIT_ORG, GIT_REPO);
		System.out.println(repo.getName());

		repoService.getBranches(repo);

//		RepositoryId prRepoId = new RepositoryId(GIT_ORG, GIT_REPO);

		PullRequestService prService = new PullRequestService(client);
		PullRequest pr = new PullRequest();
		pr.setBase(new PullRequestMarker().setRepo(repo).setLabel("master"));
		pr.setHead(new PullRequestMarker().setRepo(repo).setLabel("MutSelfDamage.rar"));
		pr.setTitle("MutSelfDamage.rar");
		pr.setBody("A pull request for MutSelfDamage.rar");

//		prService.createPullRequest(repo, 0, "master", "MutSelfDamage.rar");

		System.out.println(prService.createPullRequest(repo, pr));
	}
}
