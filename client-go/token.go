package sidecarclient

import "context"

// TokenProvider resolves a bearer token at request time.
type TokenProvider func(context.Context) (string, error)

// Token calls the provider.
func (p TokenProvider) Token(ctx context.Context) (string, error) {
	if p == nil {
		return "", nil
	}
	return p(ctx)
}

// FixedToken returns a request-time provider for a static bearer token.
func FixedToken(token string) TokenProvider {
	return func(context.Context) (string, error) {
		return token, nil
	}
}
