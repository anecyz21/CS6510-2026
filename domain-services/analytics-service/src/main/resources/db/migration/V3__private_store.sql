-- Private store (004 research R2): the published-popularity view is no longer read from outside
-- Analytics; the public route is served by Analytics itself.
DROP VIEW published_popularity;
