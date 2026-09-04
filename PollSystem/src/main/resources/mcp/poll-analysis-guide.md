# Poll analysis guide

## Purpose
Analyze a poll using live application data only.

## Required inputs
- Poll ID

## Required sources
1. Read the MCP resource `poll://{pollId}/details`
2. Call the MCP tool `get_poll_votes`

## Procedure
1. Extract the poll title and the available options from `poll://{pollId}/details`.
2. Retrieve the live vote counts using `get_poll_votes`.
3. Compute the exact percentage for each option over the total number of votes (Votes for Option / Total Votes * 100).
4. Identify the option with the highest vote count as the current leader/winner.
5. If total votes are zero, explicitly state that the poll has no votes yet and skip percentage calculations.

## Constraints
- Do not guess poll options.
- Do not use mock data.
- Do not use workspace files as a source of truth.
- Use only MCP resources and MCP tools provided by the server.

## Output requirements
Format the final response clearly using the following structure:
- **Poll Title**: [Title]
- **Total Votes**: [Total Count]
- **Results Table/List**: Show each option with its absolute vote count and computed percentage.
- **Current Outcome**: Identify the winning option (or state if it's a tie, or note explicitly if there are no votes yet).
